package com.interviewagent.ai.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioTranscriptionServiceTest {
    @Test void queryNetworkFailuresRetryTheSameTaskForImportsAndVoice() throws Exception {
        var mapper = new ObjectMapper();
        for (boolean imported : new boolean[]{true, false}) {
            var service = spy(new AudioTranscriptionService(mapper,"id","secret","ap-shanghai","16k_zh"));
            var query = java.util.Map.of("TaskId", java.math.BigInteger.valueOf(123));
            doReturn(mapper.readTree("{\"Data\":{\"TaskId\":123}}")).when(service).callTencent(eq("CreateRecTask"), anyMap());
            doThrow(new java.net.http.HttpConnectTimeoutException("connect timed out"))
                .doThrow(new java.net.SocketException("connection reset"))
                .doReturn(mapper.readTree("{\"Data\":{\"Status\":2,\"Result\":\"原文\"}}"))
                .when(service).callTencent("DescribeTaskStatus", query);
            assertEquals("原文", imported ? service.transcribeImport("task",new byte[44],13,1534000).text() : service.transcribe("user",new byte[44],"audio/wav"));
            verify(service, times(1)).callTencent(eq("CreateRecTask"), anyMap());
            verify(service, times(3)).callTencent("DescribeTaskStatus", query);
        }
    }
    @Test void persistentTimeoutStopsAfterTwoRetries() throws Exception {
        var mapper = new ObjectMapper();
        var service = spy(new AudioTranscriptionService(mapper,"id","secret","ap-shanghai","16k_zh"));
        doReturn(mapper.readTree("{\"Data\":{\"TaskId\":123}}")).when(service).callTencent(eq("CreateRecTask"), anyMap());
        doThrow(new java.net.http.HttpTimeoutException("request timed out")).when(service).callTencent(eq("DescribeTaskStatus"), anyMap());
        var error = assertThrows(IllegalStateException.class, () -> service.transcribeImport("task",new byte[44],13,0));
        assertInstanceOf(java.net.http.HttpTimeoutException.class, error.getCause());
        verify(service, times(3)).callTencent(eq("DescribeTaskStatus"), anyMap());
        verify(service, times(1)).callTencent(eq("CreateRecTask"), anyMap());
    }
    @Test void creationFailuresAndInvalidResponsesAreNotRetried() throws Exception {
        var mapper = new ObjectMapper();
        var service = spy(new AudioTranscriptionService(mapper,"id","secret","ap-shanghai","16k_zh"));
        doThrow(new java.net.http.HttpTimeoutException("unknown submission outcome")).when(service).callTencent(eq("CreateRecTask"), anyMap());
        assertThrows(IllegalStateException.class, () -> service.transcribe("user",new byte[44],"audio/wav"));
        verify(service, times(1)).callTencent(eq("CreateRecTask"), anyMap());
        verify(service, never()).callTencent(eq("DescribeTaskStatus"), anyMap());
        for (Exception failure : new Exception[]{new IllegalStateException("AuthFailure"), new com.fasterxml.jackson.core.JsonParseException(null,"invalid JSON")}) {
            clearInvocations(service);
            doReturn(mapper.readTree("{\"Data\":{\"TaskId\":123}}")).when(service).callTencent(eq("CreateRecTask"), anyMap());
            doThrow(failure).when(service).callTencent(eq("DescribeTaskStatus"), anyMap());
            assertThrows(IllegalStateException.class, () -> service.transcribe("user",new byte[44],"audio/wav"));
            verify(service, times(1)).callTencent(eq("DescribeTaskStatus"), anyMap());
        }
    }
    @Test void queryInterruptionPreservesCancellation() throws Exception {
        var mapper = new ObjectMapper();
        var service = spy(new AudioTranscriptionService(mapper,"id","secret","ap-shanghai","16k_zh"));
        doReturn(mapper.readTree("{\"Data\":{\"TaskId\":123}}")).when(service).callTencent(eq("CreateRecTask"), anyMap());
        doThrow(new InterruptedException("cancelled")).when(service).callTencent(eq("DescribeTaskStatus"), anyMap());
        try {
            assertThrows(IllegalStateException.class, () -> service.transcribe("user",new byte[44],"audio/wav"));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(service, times(1)).callTencent(eq("DescribeTaskStatus"), anyMap());
        } finally { Thread.interrupted(); }
    }
    @Test void sharedHttpClientUsesLongerTimeoutsForEveryRequest() throws Exception {
        var service = new AudioTranscriptionService(new ObjectMapper(),"id","secret","ap-shanghai","16k_zh");
        var existing = (java.net.http.HttpClient)org.springframework.test.util.ReflectionTestUtils.getField(service,"http");
        assertEquals(java.time.Duration.ofSeconds(30), existing.connectTimeout().orElseThrow());
        var http = mock(java.net.http.HttpClient.class);
        var response = mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"Response\":{\"Data\":{\"TaskId\":123}}}");
        when(http.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class))).thenReturn(response);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"http",http);
        service.callTencent("CreateRecTask",java.util.Map.of());
        service.callTencent("DescribeTaskStatus",java.util.Map.of("TaskId",123));
        var requests = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        verify(http,times(2)).send(requests.capture(),any(java.net.http.HttpResponse.BodyHandler.class));
        for (var request : requests.getAllValues()) assertEquals(java.time.Duration.ofSeconds(60),request.timeout().orElseThrow());
    }
    @Test void importSettingsAreIndependentOfSharedVoiceTranscription() throws Exception {
        var mapper=new ObjectMapper(); var requests=new java.util.ArrayList<java.util.Map<String,?>>();
        var result=mapper.readTree("{\"Data\":{\"Status\":2,\"ResultDetail\":[{\"FinalSentence\":\"原文\",\"StartMs\":0,\"EndMs\":100,\"SpeakerId\":0}]}}");
        var service=new AudioTranscriptionService(mapper,"id","secret","ap-shanghai","16k_zh") {
            @Override JsonNode callTencent(String action,java.util.Map<String,?> params) throws Exception {
                if(action.equals("CreateRecTask")) { requests.add(params); return mapper.readTree("{\"Data\":{\"TaskId\":1}}"); }
                return result;
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(service,"importEngine","16k_zh_en_meeting");
        org.springframework.test.util.ReflectionTestUtils.setField(service,"importHotwords","React|5");
        assertEquals("原文",service.transcribe("user",new byte[44],"audio/wav"));
        var shared=requests.getFirst(); assertEquals("16k_zh",shared.get("EngineModelType")); assertEquals(1,shared.get("ResTextFormat")); assertFalse(shared.containsKey("SpeakerDiarization")); assertFalse(shared.containsKey("HotwordList"));
        service.transcribeImport("task",new byte[44],1,118000);
        var imported=requests.getLast(); assertEquals("16k_zh_en_meeting",imported.get("EngineModelType")); assertEquals(2,imported.get("ResTextFormat")); assertEquals(1,imported.get("SpeakerDiarization")); assertFalse(imported.containsKey("SpeakerNumber")); assertEquals("React|5",imported.get("HotwordList")); assertEquals(44,imported.get("DataLen"));
    }
    @Test void offsetsAndSpeakerIdsAreLocalAndOriginalTextIsPreserved() throws Exception {
        var node=new ObjectMapper().readTree("{\"ResultDetail\":[{\"FinalSentence\":\"React？\",\"SpeakerId\":0,\"StartMs\":20,\"EndMs\":500},{\"FinalSentence\":\"我用过。\",\"SpeakerId\":1,\"StartMs\":600,\"EndMs\":900}]}");
        var first=AudioTranscriptionService.decode(node,0,0); var second=AudioTranscriptionService.decode(node,1,118000);
        assertEquals(118020L,second.turns().getFirst().startMs()); assertEquals(118900L,second.turns().getLast().endMs());
        assertEquals(0,second.turns().getFirst().speakerId()); assertNotEquals(first.turns().getFirst().localSpeaker(),second.turns().getFirst().localSpeaker());
        assertEquals("React？\n我用过。",second.text());
    }
    @Test void validatesHotwordLimits() {
        assertDoesNotThrow(()->AudioTranscriptionService.validateHotwords("JavaScript|5,Node.js|5,Base64|11","16k_zh_en_meeting"));
        for(String value:java.util.List.of("React|0","React|12","|5","a".repeat(31)+"|5","一".repeat(11)+"|5","React|5,")) assertThrows(IllegalStateException.class,()->AudioTranscriptionService.validateHotwords(value,"16k_zh"));
        assertThrows(IllegalStateException.class,()->AudioTranscriptionService.validateHotwords("React|100","16k_zh_en_meeting"));
        assertThrows(IllegalStateException.class,()->AudioTranscriptionService.validateHotwords("React|5,".repeat(128)+"React|5","16k_zh"));
    }
}

package com.interviewagent.ai.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioTranscriptionServiceTest {
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

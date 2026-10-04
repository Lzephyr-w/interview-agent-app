package com.interviewagent.interview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import com.interviewagent.ai.storage.AudioTranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:interview-import-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class InterviewImportControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @MockBean AudioTranscriptionService transcription;
    @MockBean ReviewModelClient model;

    private JsonNode output(String value) throws Exception {
        JsonNode node=json.readTree(value);
        // Preserve invalid/empty outputs for validation tests; one source turn per question/answer is supplied below.
        var root=json.createObjectNode(); var roles=root.putArray("roles"); var questions=root.putArray("questions");
        int i=0;
        for(JsonNode q:node.path("questions")) {
            var question=questions.addObject(); question.putArray("questionTurnIds").add(i++); question.putArray("answerTurnIds").add(i++);
            question.put("kind","QA"); question.put("question",q.path("question").asText()); question.put("answer",q.path("answer").asText()); question.putArray("notes"); question.putArray("edits");
            if(q.path("question").asText().isBlank()) question.putArray("questionTurnIds").removeAll();
        }
        for(int j=0;j<i;j++) { var role=roles.addObject(); role.put("turnId",j); role.put("role",j%2==0?"INTERVIEWER":"CANDIDATE"); }
        return root;
    }
    private JsonNode topic(String sources,String question,String answer) throws Exception {
        var root=json.readTree(sources);
        for(var q:root.path("questions")) { var node=(com.fasterxml.jackson.databind.node.ObjectNode)q; node.put("kind","QA"); node.put("question",question); node.put("answer",answer); node.putArray("notes"); node.putArray("edits"); }
        return root;
    }
    private static AudioTranscriptionService.Transcript transcript(String text) {
        // Existing contract tests focus on workflow; actual acoustic details are covered separately.
        String[] parts=text.split("(?=面试官：|候选人：)");
        java.util.List<AudioTranscriptionService.Turn> turns=new java.util.ArrayList<>();
        for(int i=0;i<parts.length;i++) turns.add(new AudioTranscriptionService.Turn(0,i%2,(long)i*1000,(long)(i+1)*1000,parts[i]));
        return new AudioTranscriptionService.Transcript(text,turns);
    }
    @Test void importsOrderedQuestionsAndConfirmIsIdempotent() throws Exception {
        when(transcription.transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(transcript("面试官：你如何处理缓存一致性？候选人：我会双删并监控。面试官：如何验证？候选人：压测和回归测试。"));
        when(model.organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(output("{\"questions\":[{\"question\":\"你如何处理缓存一致性？\",\"answer\":\"我会双删并监控。\",\"orderIndex\":1,\"speakerEvidence\":\"面试官/候选人\"},{\"question\":\"如何验证？\",\"answer\":\"压测和回归测试。\",\"orderIndex\":2,\"speakerEvidence\":\"面试官/候选人\"}]}"));
        String target = existingInterview("user-a");
        mockMvc.perform(multipart("/api/v1/interview-imports/audio").file(wav()).param("interviewId", target).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
        String task = upload("user-a", target);
        mockMvc.perform(get("/api/v1/interview-imports/{id}", task).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
        String body = "{\"questions\":[{\"question\":\"你如何处理缓存一致性？\",\"answer\":\"我会双删并监控。\",\"orderIndex\":1,\"speakerEvidence\":\"\"},{\"question\":\"如何验证？\",\"answer\":\"压测和回归测试。\",\"orderIndex\":2,\"speakerEvidence\":\"\"}]}";
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm", task).with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        MvcResult saved = mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm", task).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.questions.length()").value(2)).andReturn();
        String interview = json.readTree(saved.getResponse().getContentAsString()).path("interview").path("id").asText();
        org.junit.jupiter.api.Assertions.assertEquals(target, interview);
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm", task).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.interview.id").value(interview));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM interviews WHERE id=:id").param("id", interview).query(Integer.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id=:id").param("id", interview).query(Integer.class).single());
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze", task).param("force", "true").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SAVED"));
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(1)).organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test void readyTaskCanForceFreshAnalysisWithoutAsrAndKeepsCorrectedRoles() throws Exception {
        when(transcription.transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(transcript("面试官：缓存？候选人：不知道。"));
        when(model.organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(output("{\"questions\":[{\"question\":\"缓存？\",\"answer\":\"不知道。\"}]}"));
        String id = upload("fresh-user");
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze", id).with(jwt().jwt(t -> t.subject("fresh-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions.length()").value(1));
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(1)).organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt());
        String detail = jdbc.sql("SELECT transcript_json FROM interview_audio_imports WHERE id=:id").param("id", id).query(String.class).single();
        JsonNode turns = json.readTree(detail);
        ((com.fasterxml.jackson.databind.node.ObjectNode) turns.get(1)).put("roleCorrected", true);
        jdbc.sql("UPDATE interview_audio_imports SET transcript_json=:turns WHERE id=:id").param("turns", turns.toString()).param("id", id).update();
        when(model.organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(json.readTree("{\"roles\":[{\"turnId\":1,\"role\":\"INTERVIEWER\"}],\"questions\":[]}"));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze", id).param("force", "true").with(jwt().jwt(t -> t.subject("other-user"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze", id).param("force", "true").with(jwt().jwt(t -> t.subject("fresh-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.questions[0].kind").value("UNASSIGNED"))
            .andExpect(jsonPath("$.transcript").value("面试官：缓存？\n候选人：不知道。"))
            .andExpect(jsonPath("$.turns[1].role").value("CANDIDATE")).andExpect(jsonPath("$.turns[1].roleCorrected").value(true));
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(transcription, org.mockito.Mockito.times(1)).transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test void failedFreshAnalysisKeepsPreviousQuestionsAndCanRetryButCannotRestartActiveTask() throws Exception {
        when(transcription.transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(transcript("面试官：缓存？候选人：不知道。"));
        when(model.organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(output("{\"questions\":[{\"question\":\"缓存？\",\"answer\":\"不知道。\"}]}"));
        String id = upload("fresh-failed-user");
        String progress = jdbc.sql("SELECT analysis_progress_json FROM interview_audio_imports WHERE id=:id").param("id", id).query(String.class).single();
        jdbc.sql("UPDATE interview_audio_imports SET status='ANALYZING' WHERE id=:id").param("id", id).update();
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze", id).param("force", "true").with(jwt().jwt(t -> t.subject("fresh-failed-user"))))
            .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(progress, jdbc.sql("SELECT analysis_progress_json FROM interview_audio_imports WHERE id=:id").param("id", id).query(String.class).single());
        jdbc.sql("UPDATE interview_audio_imports SET status='READY' WHERE id=:id").param("id", id).update();
        org.mockito.Mockito.doThrow(new ReviewFailedException("AUTHENTICATION", "auth", null)).when(model).organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt());
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze", id).param("force", "true").with(jwt().jwt(t -> t.subject("fresh-failed-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANALYSIS_FAILED")).andExpect(jsonPath("$.questions.length()").value(1));
        org.mockito.Mockito.doReturn(json.readTree("{\"roles\":[],\"questions\":[]}")).when(model).organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt());
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze", id).with(jwt().jwt(t -> t.subject("fresh-failed-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.questions[0].kind").value("UNASSIGNED"));
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(3)).organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(transcription, org.mockito.Mockito.times(1)).transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test void rejectsInvalidAudioAndInvalidModelOutputWithoutCreatingInterview() throws Exception {
        int before = jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id='user-a'").query(Integer.class).single();
        MockMultipartFile empty = new MockMultipartFile("file", "empty.wav", "audio/wav", new byte[0]);
        mockMvc.perform(multipart("/api/v1/interview-imports/audio").file(empty).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadRequest());
        MockMultipartFile bad = new MockMultipartFile("file", "fake.mp3", "audio/mpeg", "not audio".getBytes());
        mockMvc.perform(multipart("/api/v1/interview-imports/audio").file(bad).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadRequest());
        when(transcription.transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(transcript("一段转写"));
        when(model.organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(output("{\"questions\":[{\"question\":\"\",\"answer\":\"回答\",\"orderIndex\":1}]}"));
        String task = upload("user-a");
        mockMvc.perform(get("/api/v1/interview-imports/{id}", task).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANALYSIS_FAILED"));
        org.junit.jupiter.api.Assertions.assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id='user-a'").query(Integer.class).single());
    }

    @Test void keepsTranscriptReadyWhenModelFindsNoInterviewQuestions() throws Exception {
        when(transcription.transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(transcript("你好，我叫张明，之前负责过一个面试平台项目。"));
        when(model.organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(json.readTree("""
            {"roles":[{"turnId":0,"role":"CANDIDATE"}],"questions":[{"kind":"INTRODUCTION","question":"自我介绍","answer":"你好，我叫张明，之前负责过一个面试平台项目。","questionTurnIds":[],"answerTurnIds":[0],"notes":[],"edits":[]}]}
            """));
        String task = upload("user-a");
        mockMvc.perform(get("/api/v1/interview-imports/{id}", task).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.transcript").value("你好，我叫张明，之前负责过一个面试平台项目。"))
            .andExpect(jsonPath("$.questions[0].kind").value("INTRODUCTION"));
        org.mockito.Mockito.verify(transcription).transcribeImport(anyString(), org.mockito.ArgumentMatchers.argThat(bytes -> bytes.length > 0), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(0L));
        org.junit.jupiter.api.Assertions.assertTrue(jdbc.sql("SELECT object_path FROM interview_audio_imports WHERE id=:id").param("id", task).query(String.class).optional().isEmpty());
    }

    @Test void transcodesLargeAudioIntoTencentSafeChunks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpegAvailable(), "FFmpeg is required to run this integration test.");
        var temporaryBefore=temporaryAudio();
        byte[] audio = wav(5_000_044);
        when(transcription.transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            int segment=call.getArgument(2); long offset=call.getArgument(3);
            return new AudioTranscriptionService.Transcript("转写结果",java.util.List.of(new AudioTranscriptionService.Turn(segment,segment%2,offset,offset+1000,"转写结果")));
        });
        when(model.organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(output("{\"questions\":[]}"));
        MvcResult result = mockMvc.perform(multipart("/api/v1/interview-imports/audio")
                .file(new MockMultipartFile("file", "large.wav", "audio/wav", audio))
                .with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY")).andReturn();
        String task = json.readTree(result.getResponse().getContentAsString()).path("id").asText();
        var bytes = org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.Mockito.verify(transcription, org.mockito.Mockito.atLeast(2)).transcribeImport(anyString(), bytes.capture(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
        for(byte[] part:bytes.getAllValues()) org.junit.jupiter.api.Assertions.assertTrue(part.length > 0 && part.length <= 5_000_000);
        JsonNode detail=json.readTree(result.getResponse().getContentAsString()).path("turns");
        org.junit.jupiter.api.Assertions.assertEquals(0,detail.get(0).path("segmentIndex").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(1,detail.get(1).path("segmentIndex").asInt());
        org.junit.jupiter.api.Assertions.assertTrue(detail.get(1).path("startMs").asLong()>110000);
        org.junit.jupiter.api.Assertions.assertEquals(temporaryBefore,temporaryAudio());
        org.junit.jupiter.api.Assertions.assertTrue(jdbc.sql("SELECT object_path FROM interview_audio_imports WHERE id=:id").param("id", task).query(String.class).optional().isEmpty());
    }
    private java.util.Set<java.nio.file.Path> temporaryAudio() throws Exception {
        try(var files=java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p->p.getFileName().toString().startsWith("interview-audio-parts-") || p.getFileName().toString().startsWith("interview-import-")).collect(java.util.stream.Collectors.toSet());
        }
    }
    @Test void failedAsrCleansAudioAndReuploadCreatesUsableTask() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpegAvailable());
        var before=temporaryAudio();
        when(transcription.transcribeImport(anyString(),any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyLong())).thenThrow(new IllegalStateException("ASR timeout"));
        var failed=mockMvc.perform(multipart("/api/v1/interview-imports/audio").file(new MockMultipartFile("file","large.wav","audio/wav",wav(5_000_044))).with(jwt().jwt(t->t.subject("reupload-user"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("TRANSCRIPTION_FAILED")).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(before,temporaryAudio());
        org.mockito.Mockito.verifyNoInteractions(model);
        org.mockito.Mockito.doReturn(transcript("面试官：使用React吗？候选人：没有。")).when(transcription).transcribeImport(anyString(),any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyLong());
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(output("{\"questions\":[{\"question\":\"React？\",\"answer\":\"没有\"}]}"));
        String next=upload("reupload-user");
        org.junit.jupiter.api.Assertions.assertNotEquals(json.readTree(failed.getResponse().getContentAsString()).path("id").asText(),next);
        mockMvc.perform(get("/api/v1/interview-imports/{id}",next).with(jwt().jwt(t->t.subject("reupload-user")))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
    }

    @Test void legacyRetryUsesSavedTranscriptAndRejectsInventedSpeakerCorrections() throws Exception {
        String id=java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO interview_audio_imports(id,user_id,original_filename,content_type,size_bytes,status,transcript) VALUES(:id,'legacy-user','old.wav','audio/wav',100,'ANALYSIS_FAILED','问：缓存？答：不知道。')").param("id",id).update();
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(topic("{\"roles\":[{\"turnId\":0,\"role\":\"INTERVIEWER\"},{\"turnId\":1,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}","缓存？","不知道。"));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze",id).with(jwt().jwt(t->t.subject("legacy-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.turns").isEmpty());
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/roles",id).with(jwt().jwt(t->t.subject("legacy-user"))).contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[{\"turnId\":0,\"role\":\"CANDIDATE\"}]}"))
            .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(transcription);
    }
    @Test void readingExistingGeneratedAnswerCompactsLinesWithoutAsrOrAiAndConfirmKeepsManualParagraphs() throws Exception {
        String user="format-user", target=existingInterview(user), id=java.util.UUID.randomUUID().toString();
        String raw="项目职责？\n企业宣传片。\n后期剪辑。";
        var turns=java.util.List.of(new InterviewApi.ImportTurn(0,0,0,0L,1000L,"项目职责？","INTERVIEWER",false),new InterviewApi.ImportTurn(1,0,1,1000L,2000L,"企业宣传片。","CANDIDATE",false),new InterviewApi.ImportTurn(2,0,1,2000L,3000L,"后期剪辑。","CANDIDATE",false));
        var question=new InterviewApi.ImportedQuestion("项目职责？","企业宣传片。\n后期剪辑。",1,"来源",java.util.List.of(0),java.util.List.of(1,2));
        String stored=json.writeValueAsString(java.util.Map.of("questions",java.util.List.of(question)));
        jdbc.sql("INSERT INTO interview_audio_imports(id,user_id,target_interview_id,original_filename,content_type,size_bytes,status,transcript,transcript_json,analysis_json) VALUES(:id,:user,:target,'old.wav','audio/wav',100,'READY',:raw,:turns,:analysis)")
            .param("id",id).param("user",user).param("target",target).param("raw",raw).param("turns",json.writeValueAsString(turns)).param("analysis",stored).update();
        mockMvc.perform(get("/api/v1/interview-imports/{id}",id).with(jwt().jwt(t->t.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answer").value("企业宣传片。 后期剪辑。"))
            .andExpect(jsonPath("$.transcript").value(raw));
        org.mockito.Mockito.verifyNoInteractions(model,transcription);
        org.junit.jupiter.api.Assertions.assertEquals(stored,jdbc.sql("SELECT analysis_json FROM interview_audio_imports WHERE id=:id").param("id",id).query(String.class).single());
        String edited="企业宣传片。\n\n后期剪辑及人工补充。";
        var body=java.util.Map.of("questions",java.util.List.of(new InterviewApi.ImportedQuestion("项目职责？",edited,1,"来源")));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answerText").value(edited));
    }

    @Test void retriesOnlyFailedBlocksAndRoleCorrectionNeverRetranscribes() throws Exception {
        var turns=java.util.List.of(new AudioTranscriptionService.Turn(0,0,0L,1000L,"如何设计缓存？"),
            new AudioTranscriptionService.Turn(0,1,1000L,2000L,"甲".repeat(8000)),new AudioTranscriptionService.Turn(1,0,2000L,3000L,"乙".repeat(8000)));
        when(transcription.transcribeImport(anyString(),any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyLong())).thenReturn(new AudioTranscriptionService.Transcript("原话",turns));
        JsonNode first=topic("{\"roles\":[{\"turnId\":0,\"role\":\"INTERVIEWER\"},{\"turnId\":1,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}","如何设计缓存？","甲".repeat(8000));
        JsonNode next=topic("{\"roles\":[{\"turnId\":2,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1,2]}]}","如何设计缓存？","甲".repeat(8000)+"乙".repeat(8000));
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(first).thenThrow(new ReviewFailedException("AUTHENTICATION","auth",null)).thenReturn(next);
        String id=upload("checkpoint-user");
        mockMvc.perform(get("/api/v1/interview-imports/{id}",id).with(jwt().jwt(t->t.subject("checkpoint-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANALYSIS_FAILED")).andExpect(jsonPath("$.questions.length()").value(1));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze",id).with(jwt().jwt(t->t.subject("checkpoint-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.questions.length()").value(1));
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(3)).organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt());
        String roles="{\"roles\":[{\"turnId\":2,\"role\":\"INTERVIEWER\"}]}";
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/roles",id).with(jwt().jwt(t->t.subject("other-user"))).contentType(MediaType.APPLICATION_JSON).content(roles)).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/roles",id).with(jwt().jwt(t->t.subject("checkpoint-user"))).contentType(MediaType.APPLICATION_JSON).content(roles))
            .andExpect(status().isOk()).andExpect(jsonPath("$.turns[2].role").value("INTERVIEWER")).andExpect(jsonPath("$.turns[2].roleCorrected").value(true));
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(first).thenReturn(json.readTree("{\"roles\":[],\"questions\":[]}"));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze",id).with(jwt().jwt(t->t.subject("checkpoint-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.questions[0].answer").value("甲".repeat(8000)));
        org.mockito.Mockito.verify(transcription,org.mockito.Mockito.times(1)).transcribeImport(anyString(),any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyLong());
    }

    @Test void truncatedTopicCheckpointResumesWithoutReplayingCompletedHalf() throws Exception {
        when(transcription.transcribeImport(anyString(),any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(transcript("面试官：请求封装？候选人：Axios。面试官：缓存策略？候选人：Redis。"));
        var first=topic("{\"roles\":[{\"turnId\":0,\"role\":\"INTERVIEWER\"},{\"turnId\":1,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}","请求封装？","Axios。");
        var next=topic("{\"roles\":[{\"turnId\":2,\"role\":\"INTERVIEWER\"},{\"turnId\":3,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[2],\"answerTurnIds\":[3]}]}","缓存策略？","Redis。");
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt()))
            .thenThrow(new ReviewFailedException("TRUNCATED","length",null)).thenThrow(new ReviewFailedException("TRUNCATED","length",null))
            .thenReturn(first).thenThrow(new ReviewFailedException("AUTHENTICATION","auth",null)).thenReturn(next);
        String id=upload("split-checkpoint-user");
        mockMvc.perform(get("/api/v1/interview-imports/{id}",id).with(jwt().jwt(t->t.subject("split-checkpoint-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANALYSIS_FAILED")).andExpect(jsonPath("$.questions.length()").value(1));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze",id).with(jwt().jwt(t->t.subject("split-checkpoint-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.questions.length()").value(2));
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(5)).organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(transcription,org.mockito.Mockito.times(1)).transcribeImport(anyString(),any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyLong());
    }

    @Test void rejectsOverLimitAndInvalidConfirmWithoutPartialInterview() throws Exception {
        int before = jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id='user-a'").query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(800_000_000L, InterviewImportService.MAX_AUDIO_BYTES);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> InterviewImportService.validateAudioSize(InterviewImportService.MAX_AUDIO_BYTES));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> InterviewImportService.validateAudioSize(InterviewImportService.MAX_AUDIO_BYTES + 1));
        when(transcription.transcribeImport(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(transcript("转写"));
        when(model.organizeImportJson(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(output("{\"questions\":[{\"question\":\"问题\",\"answer\":\"回答\",\"orderIndex\":1,\"speakerEvidence\":\"\"}]}"));
        String task = upload("user-a");
        String packageId = packageFor("user-a");
        String invalid = "{\"interview\":{\"company\":\"A\",\"role\":\"后端\",\"interviewRound\":\"一面\",\"interviewTime\":\"2026-08-16T10:00:00+08:00\",\"interviewPackageId\":\"" + packageId + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"},\"questions\":[{\"question\":\"问题\",\"answer\":\"回答\",\"orderIndex\":2,\"speakerEvidence\":\"\"}]}";
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm", task).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id='user-a'").query(Integer.class).single());
    }

    @Test void topicDraftUsesOnlyOwnedLinkedMaterialsAndKeepsRawWhenRestoringOrEditing() throws Exception {
        String user="resume-preview-user",target=existingInterview(user),raw="使用什么工具？\n我用过靠带。\n";
        jdbc.sql("UPDATE resume_files SET parsed_status='READY',parsed_text='工具：Codex。' WHERE id=(SELECT p.resume_file_id FROM interview_packages p JOIN interviews i ON i.interview_package_id=p.id WHERE i.id=:id)").param("id",target).update();
        String other=existingInterview("other-resume-user");
        jdbc.sql("UPDATE resume_files SET parsed_status='READY',parsed_text='OTHER_USER_SECRET' WHERE user_id='other-resume-user'").update();
        linkCard(user,target,"请求封装","Axios、baseURL、localStorage");
        linkCard("other-resume-user",other,"OTHER_CARD_SECRET","SECRET_STACK");
        var result=json.readTree("""
            {"roles":[{"turnId":0,"role":"INTERVIEWER"},{"turnId":1,"role":"CANDIDATE"}],
             "questions":[{"kind":"QA","question":"使用什么工具？","answer":"我用过Codex。","questionTurnIds":[0],"answerTurnIds":[1],"notes":[],
             "edits":[{"turnId":1,"original":"靠带","replacement":"Codex","evidenceSource":"RESUME","evidenceId":"","evidence":"Codex","reason":"本场简历名称","uncertain":false}]}]}
            """);
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(result);
        mockMvc.perform(post("/api/v1/interview-imports/text").with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("interviewId",other,"transcript",raw)))).andExpect(status().isNotFound());
        var preview=mockMvc.perform(post("/api/v1/interview-imports/text").with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("interviewId",target,"transcript",raw))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("TEXT")).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.resume.status").value("READY")).andExpect(jsonPath("$.questions[0].answer").value("我用过Codex。"))
            .andExpect(jsonPath("$.questions[0].edits[0].replacement").value("Codex")).andExpect(jsonPath("$.organization").value(ImportOrganization.VERSION))
            .andExpect(jsonPath("$.evidenceCards[0]").value("请求封装"))
            .andExpect(jsonPath("$.transcript").value(raw)).andReturn();
        JsonNode task=json.readTree(preview.getResponse().getContentAsString()); String id=task.path("id").asText();
        var prompts=org.mockito.ArgumentCaptor.forClass(String.class); org.mockito.Mockito.verify(model).organizeImportJson(prompts.capture(),org.mockito.ArgumentMatchers.anyInt());
        org.junit.jupiter.api.Assertions.assertTrue(prompts.getValue().contains("工具：Codex。"));
        org.junit.jupiter.api.Assertions.assertTrue(prompts.getValue().contains("Axios、baseURL、localStorage"));
        org.junit.jupiter.api.Assertions.assertFalse(prompts.getValue().contains("OTHER_USER_SECRET"));
        org.junit.jupiter.api.Assertions.assertFalse(prompts.getValue().contains("OTHER_CARD_SECRET"));
        org.mockito.Mockito.verifyNoInteractions(transcription);
        var body=json.createObjectNode(); body.set("questions",task.path("questions"));
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/draft",id).with(jwt().jwt(t->t.subject("other-resume-user"))).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isNotFound());
        var accepted=mockMvc.perform(patch("/api/v1/interview-imports/{id}/draft",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answer").value("我用过Codex。"))
            .andExpect(jsonPath("$.turns[1].text").value("我用过靠带。")).andExpect(jsonPath("$.transcript").value(raw)).andReturn();
        body.set("questions",json.readTree(accepted.getResponse().getContentAsString()).path("questions"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("questions").get(0)).put("answer","我用过靠带。");
        var revoked=mockMvc.perform(patch("/api/v1/interview-imports/{id}/draft",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answer").value("我用过靠带。")).andReturn();
        body.set("questions",json.readTree(revoked.getResponse().getContentAsString()).path("questions"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("questions").get(0)).put("answer","手工核对\n\n原文段落");
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/draft",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answer").value("手工核对\n\n原文段落"));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answerText").value("手工核对\n\n原文段落"));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions.length()").value(1));
    }
    @Test void invalidCorrectionKeepsPreviewAndCannotClearReviewWarningFromTheClient() throws Exception {
        String user="invalid-edit-user",target=existingInterview(user),raw="请求怎么封装？创建Excel实例。";
        var result=topic("{\"roles\":[{\"turnId\":0,\"role\":\"INTERVIEWER\"},{\"turnId\":1,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}","请求封装？","创建Axios实例。");
        ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("questions").get(0)).withArray("edits").addObject()
            .put("turnId",1).put("original","不存在的原词").put("replacement","Axios").put("evidenceSource","RESUME").put("evidenceId","").put("evidence","Axios").put("reason","术语核对").put("uncertain",false);
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(result);
        var response=mockMvc.perform(post("/api/v1/interview-imports/text").with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("interviewId",target,"transcript",raw))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.questions[0].answer").value("创建Excel实例。"))
            .andExpect(jsonPath("$.questions[0].warnings[0].code").value("EDIT_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.questions[0].edits.length()").value(0)).andExpect(jsonPath("$.transcript").value(raw)).andReturn();
        var task=json.readTree(response.getResponse().getContentAsString()); String id=task.path("id").asText();
        var body=json.createObjectNode(); body.set("questions",task.path("questions"));
        var question=(com.fasterxml.jackson.databind.node.ObjectNode)body.path("questions").get(0); question.putArray("warnings");
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/draft",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].warnings[0].code").value("EDIT_VALIDATION_FAILED"));
        question.put("reviewConfirmed",true);
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answerText").value("创建Excel实例。"));
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verifyNoInteractions(transcription);
    }

    @Test void uncertainAnswersRequireExplicitReviewOrExclusionAndCannotHideSources() throws Exception {
        String user="pending-preview-user",target=existingInterview(user);
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(topic("{\"roles\":[{\"turnId\":0,\"role\":\"INTERVIEWER\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}","问题？","实际回答。"));
        var response=mockMvc.perform(post("/api/v1/interview-imports/text").with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("interviewId",target,"transcript","问题？实际回答。"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.resume.status").value("PENDING"))
            .andExpect(jsonPath("$.questions[0].warnings[0].code").value("ANSWER_ROLE_UNCERTAIN")).andExpect(jsonPath("$.turns[1].role").value("UNKNOWN")).andReturn();
        JsonNode task=json.readTree(response.getResponse().getContentAsString()); String id=task.path("id").asText();
        var body=json.createObjectNode(); body.set("questions",task.path("questions")); var question=(com.fasterxml.jackson.databind.node.ObjectNode)body.path("questions").get(0);
        question.putArray("warnings");
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest());
        var hidden=body.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)hidden.path("questions").get(0)).putArray("questionTurnIds");
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/draft",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(hidden.toString())).andExpect(status().isBadRequest());
        var replaced=json.createObjectNode(); replaced.set("questions",json.valueToTree(java.util.List.of(new InterviewApi.ImportedQuestion("手工问题","手工回答",1,""))));
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(replaced.toString())).andExpect(status().isBadRequest());
        question.put("reviewConfirmed",true);
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/draft",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].reviewConfirmed").value(true)).andExpect(jsonPath("$.questions[0].warnings.length()").value(1));
        // Explicit exclusion is audited and is the only way to remove a pending source before saving other items.
        replaced.putArray("excludedQuestionIds").add("QA:0");
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(replaced.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answerText").value("手工回答"));
        mockMvc.perform(get("/api/v1/interview-imports/{id}",id).with(jwt().jwt(t->t.subject(user)))).andExpect(jsonPath("$.excludedQuestionIds[0]").value("QA:0"));
        org.mockito.Mockito.verifyNoInteractions(transcription);
    }
    @Test void cacheContractAndLinkedMaterialChangesInvalidateOnlyAnalysis() throws Exception {
        String user="cache-preview-user",target=existingInterview(user);
        var result=topic("{\"roles\":[{\"turnId\":0,\"role\":\"INTERVIEWER\"},{\"turnId\":1,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}","问题？","回答。");
        when(model.organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(result);
        var response=mockMvc.perform(post("/api/v1/interview-imports/text").with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("interviewId",target,"transcript","问题？回答。")))).andExpect(status().isCreated()).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze",id).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk());
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt());
        jdbc.sql("UPDATE resume_files SET parsed_status='READY',parsed_text='不同简历' WHERE user_id=:user").param("user",user).update();
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze",id).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk()).andExpect(jsonPath("$.resume.status").value("READY"));
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(2)).organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt());
        jdbc.sql("UPDATE interview_audio_imports SET analysis_progress_json=:old WHERE id=:id").param("old",json.writeValueAsString(java.util.Map.of("0",result))).param("id",id).update();
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze",id).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk());
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(3)).organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt());
        var progress=json.readTree(jdbc.sql("SELECT analysis_progress_json FROM interview_audio_imports WHERE id=:id").param("id",id).query(String.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(ImportOrganization.VERSION,progress.path("version").asText());
        org.junit.jupiter.api.Assertions.assertTrue(progress.path("blocks").get("organized-0").has("context"));
        linkCard(user,target,"新增项目","新的证据");
        mockMvc.perform(post("/api/v1/interview-imports/{id}/analyze",id).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk()).andExpect(jsonPath("$.evidenceCards[0]").value("新增项目"));
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(4)).organizeImportJson(anyString(),org.mockito.ArgumentMatchers.anyInt());
        mockMvc.perform(patch("/api/v1/interview-imports/{id}/roles",id).with(jwt().jwt(t->t.subject(user))).contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[{\"turnId\":1,\"role\":\"CANDIDATE\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.transcript").value("问题？回答。"))
            .andExpect(jsonPath("$.turns[1].roleCorrected").value(true)).andExpect(jsonPath("$.turns[1].startMs").isEmpty());
        org.mockito.Mockito.verifyNoInteractions(transcription);
    }

    private void linkCard(String user,String target,String name,String stack) {
        String id=java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO project_evidence_cards(id,user_id,project_name,technology_stack,project_description_and_responsibilities,project_highlights) VALUES(:id,:user,:name,:stack,'职责','亮点')")
            .param("id",id).param("user",user).param("name",name).param("stack",stack).update();
        jdbc.sql("INSERT INTO interview_package_evidence_cards(interview_package_id,evidence_card_id) SELECT interview_package_id,:card FROM interviews WHERE id=:target")
            .param("card",id).param("target",target).update();
    }
    private String upload(String user) throws Exception { return upload(user, null); }
    private String upload(String user, String interviewId) throws Exception {
        var request = multipart("/api/v1/interview-imports/audio").file(wav()).with(jwt().jwt(token -> token.subject(user)));
        if (interviewId != null) request.param("interviewId", interviewId);
        MvcResult result = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }
    private MockMultipartFile wav() { return new MockMultipartFile("file", "interview.wav", "audio/wav", wav(16)); }
    private static byte[] wav(int size) {
        byte[] bytes = new byte[size];
        byte[] header = new byte[] {'R','I','F','F',0,0,0,0,'W','A','V','E','f','m','t',' ',16,0,0,0,1,0,1,0,(byte)0x80,0x3e,0,0,0,(byte)0x7d,0,0,2,0,16,0,'d','a','t','a',0,0,0,0};
        int dataSize = size - header.length;
        int riffSize = size - 8;
        for (int i = 0; i < 4; i++) { header[4 + i] = (byte) (riffSize >>> (8 * i)); header[40 + i] = (byte) (dataSize >>> (8 * i)); }
        System.arraycopy(header, 0, bytes, 0, Math.min(header.length, size));
        return bytes;
    }
    private static boolean ffmpegAvailable() throws Exception {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
            return process.exitValue() == 0;
        }
        catch (java.io.IOException exception) { return false; }
    }
    private String existingInterview(String user) throws Exception {
        String packageId = packageFor(user);
        MvcResult result = mockMvc.perform(post("/api/v1/interviews").with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON).content("{\"company\":\"A 公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"interviewTime\":\"2026-08-16T10:00:00+08:00\",\"interviewPackageId\":\"" + packageId + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"}"))
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("interview").path("id").asText();
    }
    private String packageFor(String user) {
        String resume = java.util.UUID.randomUUID().toString(), jd = java.util.UUID.randomUUID().toString(), pack = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO resume_files (id,user_id,original_filename,content_type,size_bytes,object_path) VALUES (:id,:user,'r.pdf','application/pdf',1,:path)").param("id", resume).param("user", user).param("path", "r-" + resume).update();
        jdbc.sql("INSERT INTO job_descriptions (id,user_id,company,role,content) VALUES (:id,:user,'A 公司','后端','JD')").param("id", jd).param("user", user).update();
        jdbc.sql("INSERT INTO interview_packages (id,user_id,company,role,interview_round,resume_file_id,job_description_id) VALUES (:id,:user,'A 公司','后端','技术一面',:resume,:jd)").param("id", pack).param("user", user).param("resume", resume).param("jd", jd).update();
        return pack;
    }
}

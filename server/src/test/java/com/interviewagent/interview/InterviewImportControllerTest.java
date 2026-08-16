package com.interviewagent.interview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.aimock.AiMockStorage;
import com.interviewagent.aimock.AudioTranscriptionService;
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
    @MockBean AiMockStorage storage;
    @MockBean AudioTranscriptionService transcription;
    @MockBean ReviewModelClient model;

    @Test void importsOrderedQuestionsAndConfirmIsIdempotent() throws Exception {
        when(transcription.transcribe(anyString(), any(), anyString())).thenReturn("面试官：你如何处理缓存一致性？候选人：我会双删并监控。面试官：如何验证？候选人：压测和回归测试。");
        when(model.replyJson(anyString())).thenReturn(json.readTree("{\"questions\":[{\"question\":\"你如何处理缓存一致性？\",\"answer\":\"我会双删并监控。\",\"orderIndex\":1,\"speakerEvidence\":\"面试官/候选人\"},{\"question\":\"如何验证？\",\"answer\":\"压测和回归测试。\",\"orderIndex\":2,\"speakerEvidence\":\"面试官/候选人\"}]}"));
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
    }

    @Test void rejectsInvalidAudioAndInvalidModelOutputWithoutCreatingInterview() throws Exception {
        int before = jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id='user-a'").query(Integer.class).single();
        MockMultipartFile empty = new MockMultipartFile("file", "empty.wav", "audio/wav", new byte[0]);
        mockMvc.perform(multipart("/api/v1/interview-imports/audio").file(empty).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadRequest());
        MockMultipartFile bad = new MockMultipartFile("file", "fake.mp3", "audio/mpeg", "not audio".getBytes());
        mockMvc.perform(multipart("/api/v1/interview-imports/audio").file(bad).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadRequest());
        when(transcription.transcribe(anyString(), any(), anyString())).thenReturn("一段转写");
        when(model.replyJson(anyString())).thenReturn(json.readTree("{\"questions\":[{\"question\":\"\",\"answer\":\"回答\",\"orderIndex\":1}]}"));
        String task = upload("user-a");
        mockMvc.perform(get("/api/v1/interview-imports/{id}", task).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANALYSIS_FAILED"));
        org.junit.jupiter.api.Assertions.assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id='user-a'").query(Integer.class).single());
    }

    @Test void rejectsOverLimitAndInvalidConfirmWithoutPartialInterview() throws Exception {
        int before = jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id='user-a'").query(Integer.class).single();
        byte[] tooLarge = new byte[25 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "large.wav", "audio/wav", tooLarge);
        mockMvc.perform(multipart("/api/v1/interview-imports/audio").file(file).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadRequest());
        when(transcription.transcribe(anyString(), any(), anyString())).thenReturn("转写");
        when(model.replyJson(anyString())).thenReturn(json.readTree("{\"questions\":[{\"question\":\"问题\",\"answer\":\"回答\",\"orderIndex\":1,\"speakerEvidence\":\"\"}]}"));
        String task = upload("user-a");
        String packageId = packageFor("user-a");
        String invalid = "{\"interview\":{\"company\":\"A\",\"role\":\"后端\",\"interviewRound\":\"一面\",\"interviewTime\":\"2026-08-16T10:00:00+08:00\",\"interviewPackageId\":\"" + packageId + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"},\"questions\":[{\"question\":\"问题\",\"answer\":\"回答\",\"orderIndex\":2,\"speakerEvidence\":\"\"}]}";
        mockMvc.perform(post("/api/v1/interview-imports/{id}/confirm", task).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id='user-a'").query(Integer.class).single());
    }

    private String upload(String user) throws Exception { return upload(user, null); }
    private String upload(String user, String interviewId) throws Exception {
        var request = multipart("/api/v1/interview-imports/audio").file(wav()).with(jwt().jwt(token -> token.subject(user)));
        if (interviewId != null) request.param("interviewId", interviewId);
        MvcResult result = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }
    private MockMultipartFile wav() { byte[] wav = new byte[16]; wav[0]='R'; wav[1]='I'; wav[2]='F'; wav[3]='F'; wav[8]='W'; wav[9]='A'; wav[10]='V'; wav[11]='E'; return new MockMultipartFile("file", "interview.wav", "audio/wav", wav); }
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

package com.interviewagent.mock;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.interview.ReviewModelClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:mock-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class MockInterviewControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @MockBean ReviewModelClient model;

    @BeforeEach
    void mockAi() {
        java.util.concurrent.atomic.AtomicInteger questions = new java.util.concurrent.atomic.AtomicInteger();
        when(model.reply(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0, String.class);
            if (prompt.contains("中文面试教练")) return "回答说明了你的动作；请补充可验证的结果或取舍。";
            return switch (questions.getAndIncrement()) {
                case 0 -> "请介绍一次你主导的高并发系统优化，并说明个人贡献。";
                case 1 -> "当时的性能瓶颈如何定位，又如何验证优化效果？";
                case 2 -> "请讲一次你在故障恢复中做出的关键技术取舍。";
                default -> "请说明你如何用监控指标提前发现容量风险。";
            };
        });
    }

    @Test
    void isolatesSessionAndPackageAndSavesFiniteFlowAsFormalInterview() throws Exception {
        String packageA = packageFor("user-a");

        mockMvc.perform(post("/api/v1/mock-interviews").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewPackageId\":\"" + packageA + "\"}"))
            .andExpect(status().isNotFound());

        MvcResult created = mockMvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewPackageId\":\"" + packageA + "\",\"company\":\"A 公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.aiAvailable").value(true)).andExpect(jsonPath("$.totalQuestions").value(4)).andExpect(jsonPath("$.currentQuestion.questionKind").value("MAIN")).andReturn();
        JsonNode session = objectMapper.readTree(created.getResponse().getContentAsString());
        String sessionId = session.get("id").asText();
        String firstQuestionId = session.get("currentQuestion").get("id").asText();

        mockMvc.perform(get("/api/v1/mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/mock-interviews/{id}/answer", sessionId).with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + firstQuestionId + "\",\"answerText\":\"越权\",\"selfAssessment\":\"GOOD\"}"))
            .andExpect(status().isNotFound());

        MvcResult answered = mockMvc.perform(post("/api/v1/mock-interviews/{id}/answer", sessionId).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + firstQuestionId + "\",\"answerText\":\"我负责缓存方案和上线验证。\",\"selfAssessment\":\"GOOD\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completedQuestions").value(1)).andExpect(jsonPath("$.questions[0].aiFeedback").isNotEmpty()).andExpect(jsonPath("$.currentQuestion.questionKind").value("FOLLOW_UP")).andReturn();
        String followupId = objectMapper.readTree(answered.getResponse().getContentAsString()).get("currentQuestion").get("id").asText();

        mockMvc.perform(post("/api/v1/mock-interviews/{id}/answer", sessionId).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + firstQuestionId + "\",\"answerText\":\"重复提交\",\"selfAssessment\":\"GOOD\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completedQuestions").value(1));
        mockMvc.perform(post("/api/v1/mock-interviews/{id}/skip", sessionId).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + followupId + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completedQuestions").value(2)).andExpect(jsonPath("$.questions[1].answerText").value("")).andExpect(jsonPath("$.questions[1].state").value("SKIPPED"));

        MvcResult finished = mockMvc.perform(post("/api/v1/mock-interviews/{id}/finish", sessionId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINISHED")).andExpect(jsonPath("$.formalInterviewId").isNotEmpty()).andReturn();
        String formalId = objectMapper.readTree(finished.getResponse().getContentAsString()).get("formalInterviewId").asText();
        mockMvc.perform(post("/api/v1/mock-interviews/{id}/finish", sessionId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.formalInterviewId").value(formalId));
        mockMvc.perform(get("/api/v1/interviews/{id}", formalId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.interview.result").value("UNKNOWN")).andExpect(jsonPath("$.interview.status").value("PENDING_REVIEW")).andExpect(jsonPath("$.interview.interviewType").value("MOCK"))
            .andExpect(jsonPath("$.interview.interviewPackageId").value(packageA)).andExpect(jsonPath("$.questions.length()").value(3)).andExpect(jsonPath("$.questions[0].sortOrder").value(0)).andExpect(jsonPath("$.questions[1].answerText").value(""));
        mockMvc.perform(get("/api/v1/interviews/{id}", formalId).with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/interviews/{id}", formalId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.formalInterviewId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void skippingMainDoesNotCountItsDormantFollowup() throws Exception {
        String packageA = packageFor("user-a");
        MvcResult created = mockMvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewPackageId\":\"" + packageA + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        JsonNode session = objectMapper.readTree(created.getResponse().getContentAsString());

        MvcResult skipped = mockMvc.perform(post("/api/v1/mock-interviews/{id}/skip", session.get("id").asText()).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionId\":\"" + session.get("currentQuestion").get("id").asText() + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completedQuestions").value(1))
            .andExpect(jsonPath("$.currentQuestion.questionKind").value("MAIN"))
            .andExpect(jsonPath("$.currentQuestionIndex").value(2)).andExpect(jsonPath("$.totalQuestions").value(4)).andReturn();
        for (int index = 0; index < 3; index++) {
            JsonNode current = objectMapper.readTree(skipped.getResponse().getContentAsString()).get("currentQuestion");
            skipped = mockMvc.perform(post("/api/v1/mock-interviews/{id}/skip", session.get("id").asText()).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"questionId\":\"" + current.get("id").asText() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        }
        JsonNode completed = objectMapper.readTree(skipped.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(4, completed.get("questions").size());
        org.junit.jupiter.api.Assertions.assertTrue(completed.get("currentQuestion").isNull());
    }

    private String packageFor(String user) throws Exception {
        String resumeFile = resumeFile(user);
        String jd = id(createResource("/api/v1/job-descriptions", user, "{\"company\":\"A 公司\",\"role\":\"后端\",\"content\":\"Spring\"}"));
        return id(createResource("/api/v1/interview-packages", user, "{\"company\":\"A 公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"resumeFileId\":\"" + resumeFile + "\",\"jobDescriptionId\":\"" + jd + "\",\"evidenceCardIds\":[]}"));
    }

    private String resumeFile(String user) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO resume_files (id, user_id, original_filename, content_type, size_bytes, object_path) VALUES (:id, :userId, 'resume.pdf', 'application/pdf', 8, :path)")
            .param("id", id).param("userId", user).param("path", "resumes/" + id + ".pdf").update();
        return id;
    }

    private MvcResult createResource(String path, String user, String body) throws Exception {
        return mockMvc.perform(post(path).with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
    }

    private String id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}

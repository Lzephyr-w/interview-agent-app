package com.interviewagent.interview;

import com.interviewagent.ai.ReviewModelClient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:interview-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class InterviewControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @MockBean ReviewModelClient model;

    @Test void isolatesInterviewsQuestionsReviewsAndPackageAssociations() throws Exception {
        String packageA = packageFor("user-a");
        String interview = id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"A 公司\",\"role\":\"后端工程师\",\"interviewRound\":\"技术一面\",\"interviewTime\":\"2026-08-08T10:00:00+08:00\",\"interviewPackageId\":\"" + packageA + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\",\"notes\":\"现场记录\"}"))
            .andExpect(status().isCreated()).andReturn());
        String question = id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"如何处理缓存一致性？\",\"answerText\":\"说明了双删策略。\",\"selfAssessment\":\"UNCERTAIN\"}"))
            .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(get("/api/v1/interviews/{id}", interview).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/interviews/{id}/questions/{questionId}", interview, question).with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"x\",\"answerText\":\"x\",\"selfAssessment\":\"GOOD\"}")).andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"B 公司\",\"role\":\"后端\",\"interviewRound\":\"一面\",\"interviewTime\":\"2026-08-08T10:00:00+08:00\",\"interviewPackageId\":\"" + packageA + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
    }

    @Test void reviewFailureDoesNotPersistAndInterviewDeletionCascades() throws Exception {
        String interview = createInterview("user-a", packageFor("user-a"));
        String question = id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目难点是什么？\",\"answerText\":\"待补充。\",\"selfAssessment\":\"UNANSWERED\"}"))
            .andExpect(status().isCreated()).andReturn());
        when(model.review(anyString())).thenThrow(new ReviewFailedException("AI 复盘服务请求失败，请稍后重试。"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadGateway());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id = :id").param("id", interview).query(Integer.class).single());

        jdbc.sql("INSERT INTO review_reports (id, interview_id, readiness, summary, weakness_tags) VALUES ('report-a', :interview, '准备不足', '待补充', '[]')").param("interview", interview).update();
        jdbc.sql("INSERT INTO question_reviews (id, review_report_id, interview_question_id, evaluation, answer_evidence, missing_evidence, improvement_action, recommended_answer_structure, possible_followups) VALUES ('question-review-a', 'report-a', :question, '待补充', '待补充', '待补充', '补充', 'STAR', '[]')").param("question", question).update();
        mockMvc.perform(delete("/api/v1/interviews/{id}", interview).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id = :id").param("id", interview).query(Integer.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id = :id").param("id", interview).query(Integer.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM question_reviews WHERE review_report_id = 'report-a'").query(Integer.class).single());
    }

    @Test void invalidOrOversizedAiOutputNeverPersists() throws Exception {
        String interview = createInterview("safe-output-user", packageFor("safe-output-user"));
        String question = id(mockMvc.perform(post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("safe-output-user"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目难点是什么？\",\"answerText\":\"待补充。\",\"selfAssessment\":\"UNANSWERED\"}"))
            .andExpect(status().isCreated()).andReturn());
        when(model.review(anyString())).thenReturn(objectMapper.readTree("{\"readiness\":\"基本准备\",\"summary\":\"" + "x".repeat(4_001) + "\",\"weaknessTags\":[],\"questionReviews\":[{\"questionId\":\"" + question + "\",\"evaluation\":\"待补充\",\"answerEvidence\":\"待补充\",\"missingEvidence\":\"待补充\",\"improvementAction\":\"待补充\",\"recommendedAnswerStructure\":\"待补充\",\"possibleFollowups\":[]}]}"));
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("safe-output-user"))))
            .andExpect(status().isBadGateway());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id=:id").param("id", interview).query(Integer.class).single());
    }

    @Test void validatesAndPersistsStructuredReview() throws Exception {
        String interview = createInterview("user-a", packageFor("user-a"));
        String question = id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目难点是什么？\",\"answerText\":\"我处理了缓存一致性。\",\"selfAssessment\":\"UNCERTAIN\"}"))
            .andExpect(status().isCreated()).andReturn());
        when(model.review(anyString())).thenReturn(objectMapper.readTree("{\"readiness\":\"基本准备\",\"summary\":\"回答有方向，但指标待补充。\",\"weaknessTags\":[\"项目深挖\"],\"questionReviews\":[{\"questionId\":\"" + question + "\",\"evaluation\":\"方向合理。\",\"answerEvidence\":\"说明了缓存一致性。\",\"missingEvidence\":\"指标待补充。\",\"improvementAction\":\"补充一次量化复盘。\",\"recommendedAnswerStructure\":\"背景-约束-方案-结果\",\"possibleFollowups\":[\"如何验证一致性？\"]}]}"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.readiness").value("基本准备")).andExpect(jsonPath("$.weaknessTags[0]").value("项目深挖"));
        mockMvc.perform(get("/api/v1/interviews/{id}", interview).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviews[0].questionReviews[0].missingEvidence").value("指标待补充。"));
    }

    @Test void distinguishesTextAndVoiceSimulationSources() throws Exception {
        String packageId = packageFor("user-a");
        String textInterview = createInterview("user-a", packageId);
        String voiceInterview = createInterview("user-a", packageId);
        jdbc.sql("UPDATE interviews SET interview_type = 'MOCK' WHERE id IN (:textId, :voiceId)")
            .param("textId", textInterview).param("voiceId", voiceInterview).update();
        jdbc.sql("INSERT INTO ai_mock_interviews (id, user_id, interview_package_id, company, role, interview_round, status, expires_at, final_interview_id) VALUES ('voice-session', 'user-a', :packageId, 'A 公司', '后端', '技术一面', 'FINISHED', CURRENT_TIMESTAMP, :interviewId)")
            .param("packageId", packageId).param("interviewId", voiceInterview).update();

        mockMvc.perform(get("/api/v1/interviews/{id}", textInterview).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.interview.simulationType").value("AI_TEXT"));
        mockMvc.perform(get("/api/v1/interviews/{id}", voiceInterview).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.interview.simulationType").value("AI_VOICE"));
    }

    @Test void keepsAiSimulationQuestionsReadOnly() throws Exception {
        String packageId = packageFor("user-a");
        String textInterview = createInterview("user-a", packageId);
        String voiceInterview = createInterview("user-a", packageId);
        String textQuestion = id(mockMvc.perform(post("/api/v1/interviews/{id}/questions", textInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"文本题\",\"answerText\":\"文本答\",\"selfAssessment\":\"GOOD\"}"))
            .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/interviews/{id}/questions", voiceInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"语音题\",\"answerText\":\"语音答\",\"selfAssessment\":\"GOOD\"}"))
            .andExpect(status().isCreated());
        jdbc.sql("UPDATE interviews SET interview_type = 'MOCK' WHERE id IN (:textId, :voiceId)")
            .param("textId", textInterview).param("voiceId", voiceInterview).update();
        jdbc.sql("INSERT INTO ai_mock_interviews (id, user_id, interview_package_id, company, role, interview_round, status, expires_at, final_interview_id) VALUES ('readonly-voice-session', 'user-a', :packageId, 'A 公司', '后端', '技术一面', 'FINISHED', CURRENT_TIMESTAMP, :interviewId)")
            .param("packageId", packageId).param("interviewId", voiceInterview).update();

        mockMvc.perform(post("/api/v1/interviews/{id}/questions", textInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"新增题\",\"answerText\":\"新增答\",\"selfAssessment\":\"GOOD\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/interviews/{id}/questions/{questionId}", textInterview, textQuestion).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"修改题\",\"answerText\":\"修改答\",\"selfAssessment\":\"GOOD\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/interviews/{id}/questions/{questionId}", textInterview, textQuestion).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/interviews/{id}/segment-transcript", textInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"transcript\":\"问：新增题\\n答：新增答\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/interviews/{id}/questions", voiceInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"新增题\",\"answerText\":\"新增答\",\"selfAssessment\":\"GOOD\"}")).andExpect(status().isBadRequest());
    }

    @Test void startsVoiceAnswerTimerOnlyWhenAnswerBegins() throws Exception {
        String packageId = packageFor("user-a");
        jdbc.sql("INSERT INTO ai_mock_interviews (id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES ('timer-session','user-a',:packageId,'A 公司','后端','技术一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("packageId", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions (id,ai_mock_interview_id,question_text,state,sort_order) VALUES ('timer-question','timer-session','请说明缓存策略。','OPEN',0)").update();

        mockMvc.perform(post("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/start-answer", "timer-session", "timer-question").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion.answerExpiresAt").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE id='timer-question' AND answer_started_at IS NOT NULL AND answer_expires_at IS NOT NULL").query(Integer.class).single());
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

    private String createInterview(String user, String packageId) throws Exception {
        return id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews").with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"A 公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"interviewTime\":\"2026-08-08T10:00:00+08:00\",\"interviewPackageId\":\"" + packageId + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"}"))
            .andExpect(status().isCreated()).andReturn());
    }

    private MvcResult createResource(String path, String user, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
    }

    private String id(MvcResult result) throws Exception { JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()); return body.has("id") ? body.get("id").asText() : body.path("interview").path("id").asText(); }
}

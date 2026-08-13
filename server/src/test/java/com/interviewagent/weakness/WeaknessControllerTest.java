package com.interviewagent.weakness;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:weakness-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class WeaknessControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;

    @BeforeEach void clearData() {
        jdbc.sql("DELETE FROM training_tasks").update();
        jdbc.sql("DELETE FROM interviews").update();
        jdbc.sql("DELETE FROM interview_package_evidence_cards").update();
        jdbc.sql("DELETE FROM interview_packages").update();
        jdbc.sql("DELETE FROM resume_files").update();
        jdbc.sql("DELETE FROM resumes").update();
        jdbc.sql("DELETE FROM job_descriptions").update();
        jdbc.sql("DELETE FROM project_evidence_cards").update();
    }
    @Test void aggregatesOnlyOwnedReviewsAndDeduplicatesTags() throws Exception {
        String interviewA = createInterview("user-a", packageFor("user-a"));
        String interviewB = createInterview("user-b", packageFor("user-b"));
        String reportA = insertReport(interviewA, "[\"项目深挖\",\"项目深挖\",\"系统设计\"]", "report-a");
        insertReport(interviewA, "[\"项目深挖\"]", "report-a-2");
        insertReport(interviewB, "[\"项目深挖\"]", "report-b");

        mockMvc.perform(get("/api/v1/weaknesses").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].tag").value("项目深挖")).andExpect(jsonPath("$[0].count").value(2)).andExpect(jsonPath("$[0].sources.length()").value(2)).andExpect(jsonPath("$[1].tag").value("系统设计"));
        mockMvc.perform(get("/api/v1/weaknesses").with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].tag").value("项目深挖")).andExpect(jsonPath("$[0].count").value(1)).andExpect(jsonPath("$[0].sources[0].reviewReportId").value("report-b"));
        org.junit.jupiter.api.Assertions.assertEquals("report-a", reportA);
    }

    @Test void isolatesTasksAndKeepsSnapshotWhenSourceReviewIsDeleted() throws Exception {
        String interviewA = createInterview("user-a", packageFor("user-a"));
        insertReport(interviewA, "[\"项目深挖\"]", "report-source");
        String task = id(mockMvc.perform(post("/api/v1/training-tasks").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"补充项目指标\",\"weaknessTag\":\"项目深挖\",\"action\":\"补充一组可验证指标。\",\"status\":\"NOT_STARTED\",\"sourceReviewReportId\":\"report-source\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.source.interviewId").value(interviewA)).andReturn());

        mockMvc.perform(get("/api/v1/training-tasks/{id}", task).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/training-tasks/{id}", task).with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"越权\",\"weaknessTag\":\"项目深挖\",\"action\":\"x\",\"status\":\"COMPLETED\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/training-tasks/{id}", task).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/training-tasks").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"关联他人\",\"weaknessTag\":\"项目深挖\",\"action\":\"x\",\"status\":\"NOT_STARTED\",\"sourceInterviewId\":\"" + interviewA + "\"}")).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/interviews/{id}/reviews/{reviewId}", interviewA, "report-source").with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/training-tasks/{id}", task).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("补充项目指标")).andExpect(jsonPath("$.source.interviewId").value(interviewA)).andExpect(jsonPath("$.source.reviewReportId").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(put("/api/v1/training-tasks/{id}", task).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"补充项目指标\",\"weaknessTag\":\"项目深挖\",\"action\":\"补充一组可验证指标。\",\"status\":\"COMPLETED\",\"sourceInterviewId\":\"" + interviewA + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isNotEmpty());
        mockMvc.perform(delete("/api/v1/interviews/{id}", interviewA).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/training-tasks/{id}", task).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isOk()).andExpect(jsonPath("$.source").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/v1/weaknesses").with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test void returnsAuditableRuleAndMissingEvidenceFallback() throws Exception {
        String interview = createInterview("user-a", packageFor("user-a"));
        insertReport(interview, "[\"系统设计\"]", "report-rule");
        mockMvc.perform(get("/api/v1/weaknesses").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].suggestion.action").value("练习需求、容量、架构、取舍与可靠性的结构化回答。"))
            .andExpect(jsonPath("$[0].suggestion.missingEvidence").value("待补充"));
    }

    @Test void detailIsOwnedAndPrefersExistingQuestionReviewAdvice() throws Exception {
        String interviewA = createInterview("user-a", packageFor("user-a"));
        String questionA = id(mockMvc.perform(post("/api/v1/interviews/{id}/questions", interviewA).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目指标是什么？\",\"answerText\":\"待补充\",\"selfAssessment\":\"UNANSWERED\"}"))
            .andExpect(status().isCreated()).andReturn());
        jdbc.sql("UPDATE interviews SET interview_type = 'MOCK' WHERE id = :id").param("id", interviewA).update();
        insertReport(interviewA, "[\"项目深挖\"]", "report-detail");
        jdbc.sql("INSERT INTO question_reviews (id, review_report_id, interview_question_id, evaluation, answer_evidence, missing_evidence, improvement_action, recommended_answer_structure, possible_followups) VALUES ('qr-detail', 'report-detail', :question, '待补充', '待补充', '指标待补充', '补充一组真实指标。', '背景-行动-结果', '[]')")
            .param("question", questionA).update();

        mockMvc.perform(get("/api/v1/weaknesses/{tag}", "项目深挖").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.suggestion.action").value("补充一组真实指标。"))
            .andExpect(jsonPath("$.suggestion.reason").value("优先沿用已有复盘中的逐题改进动作。"))
            .andExpect(jsonPath("$.sources[0].interviewId").value(interviewA))
            .andExpect(jsonPath("$.sources[0].interviewType").value("MOCK"))
            .andExpect(jsonPath("$.sources[0].evidence[0].questionText").value("项目指标是什么？"))
            .andExpect(jsonPath("$.sources[0].evidence[0].missingEvidence").value("指标待补充"));
        mockMvc.perform(get("/api/v1/weaknesses/{tag}", "项目深挖").with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isNotFound());
    }

    @Test void rejectsForeignReviewAssociationAndUnknownTaskTag() throws Exception {
        String interviewA = createInterview("user-a", packageFor("user-a"));
        String interviewB = createInterview("user-b", packageFor("user-b"));
        insertReport(interviewA, "[\"项目深挖\"]", "report-a-owned");
        insertReport(interviewB, "[\"项目深挖\"]", "report-b-owned");

        mockMvc.perform(post("/api/v1/training-tasks").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"越权复盘\",\"weaknessTag\":\"项目深挖\",\"action\":\"x\",\"status\":\"NOT_STARTED\",\"sourceReviewReportId\":\"report-b-owned\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/training-tasks").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"越权复盘\",\"weaknessTag\":\"项目深挖\",\"action\":\"x\",\"status\":\"NOT_STARTED\",\"sourceReviewReportId\":\"report-a-owned\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/training-tasks").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"自由标签\",\"weaknessTag\":\"自定义\",\"action\":\"x\",\"status\":\"NOT_STARTED\"}"))
            .andExpect(status().isBadRequest());
    }

    private String insertReport(String interviewId, String tags, String reportId) {
        jdbc.sql("INSERT INTO review_reports (id, interview_id, readiness, summary, weakness_tags) VALUES (:id, :interviewId, '准备不足', '待补充', :tags)")
            .param("id", reportId).param("interviewId", interviewId).param("tags", tags).update();
        return reportId;
    }

    private String packageFor(String user) throws Exception {
        String resumeFile = resumeFile(user);
        String jd = id(createResource("/api/v1/job-descriptions", user, "{\"company\":\"公司\",\"role\":\"后端\",\"content\":\"Spring\"}"));
        return id(createResource("/api/v1/interview-packages", user, "{\"company\":\"公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"resumeFileId\":\"" + resumeFile + "\",\"jobDescriptionId\":\"" + jd + "\",\"evidenceCardIds\":[]}"));
    }

    private String resumeFile(String user) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO resume_files (id, user_id, original_filename, content_type, size_bytes, object_path) VALUES (:id, :userId, 'resume.pdf', 'application/pdf', 8, :path)")
            .param("id", id).param("userId", user).param("path", "resumes/" + id + ".pdf").update();
        return id;
    }

    private String createInterview(String user, String packageId) throws Exception {
        return id(mockMvc.perform(post("/api/v1/interviews").with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"interviewTime\":\"2026-08-08T10:00:00+08:00\",\"interviewPackageId\":\"" + packageId + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"}"))
            .andExpect(status().isCreated()).andReturn());
    }

    private MvcResult createResource(String path, String user, String body) throws Exception {
        return mockMvc.perform(post(path).with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
    }

    private String id(MvcResult result) throws Exception { JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()); return body.has("id") ? body.get("id").asText() : body.path("interview").path("id").asText(); }
}

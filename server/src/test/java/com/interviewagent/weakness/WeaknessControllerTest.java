package com.interviewagent.weakness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:weakness-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class WeaknessControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @MockBean ReviewModelClient model;

    @BeforeEach void clearData() {
        jdbc.sql("DELETE FROM weakness_analyses").update();
        jdbc.sql("DELETE FROM training_tasks").update();
        jdbc.sql("DELETE FROM interviews").update();
        jdbc.sql("DELETE FROM interview_packages").update();
        jdbc.sql("DELETE FROM resume_files").update();
        jdbc.sql("DELETE FROM job_descriptions").update();
    }

    @Test void persistsOnlyValidatedItemsAndUsesCurrentUsersLatestReview() throws Exception {
        Seed a = seed("user-a", "a", "缓存如何保证一致性？");
        insertReview(a.interview(), "old-a", "旧复盘", a.question());
        insertReview(a.interview(), "z-new-a", "最新复盘", a.question());
        Seed b = seed("user-b", "b", "B 的私有问题？");
        insertReview(b.interview(), "review-b", "B 复盘", b.question());
        doReturn(output("已根据当前回答生成分析。", "系统设计", "缓存一致性回答缺少边界与验证", a.question())).when(model).replyJson(anyString());

        mockMvc.perform(post("/api/v1/weaknesses/analysis").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(false))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].title").value("缓存一致性回答缺少边界与验证"))
            .andExpect(jsonPath("$.items[0].evidence[0].questionId").value(a.question()))
            .andExpect(jsonPath("$.items[0].evidence[0].reviewReportId").value("z-new-a"))
            .andExpect(jsonPath("$.items[0].evidence[0].interviewId").value(a.interview()));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).replyJson(prompt.capture());
        org.junit.jupiter.api.Assertions.assertTrue(prompt.getValue().contains(a.question()));
        org.junit.jupiter.api.Assertions.assertFalse(prompt.getValue().contains(b.question()));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.getValue().contains("最新复盘"));
        org.junit.jupiter.api.Assertions.assertFalse(prompt.getValue().contains("旧复盘"));

        mockMvc.perform(get("/api/v1/weaknesses").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].tag").value("系统设计"));
        mockMvc.perform(get("/api/v1/weaknesses/analysis").with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(model, never()).replyJson(org.mockito.ArgumentMatchers.contains("B 的私有问题"));
    }

    @Test void rejectsInvalidOutputWithoutOverwritingStableSnapshot() throws Exception {
        Seed a = seed("user-a", "a", "如何验证缓存？");
        insertReview(a.interview(), "review-a", "复盘", a.question());
        doReturn(output("稳定分析", "系统设计", "缓存回答缺少验证", a.question())).when(model).replyJson(anyString());
        mockMvc.perform(post("/api/v1/weaknesses/analysis").with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isOk());

        for (String invalid : new String[] {
            "{\"summary\":\"x\",\"weaknesses\":[{\"tag\":\"系统设计\",\"title\":\"a\",\"diagnosis\":\"b\",\"action\":\"c\",\"evidence\":[{\"questionId\":\"" + a.question() + "\",\"reason\":\"x\"}]},{\"tag\":\"项目深挖\",\"title\":\"d\",\"diagnosis\":\"e\",\"action\":\"f\",\"evidence\":[{\"questionId\":\"" + a.question() + "\",\"reason\":\"x\"}]}]}",
            "{\"summary\":\"x\",\"weaknesses\":[{\"tag\":\"无效\",\"title\":\"a\",\"diagnosis\":\"b\",\"action\":\"c\",\"evidence\":[{\"questionId\":\"" + a.question() + "\",\"reason\":\"x\"}]}]}",
            "{\"summary\":\"x\",\"weaknesses\":[{\"tag\":\"系统设计\",\"title\":\"a\",\"diagnosis\":\"b\",\"action\":\"c\",\"evidence\":[{\"questionId\":\"unknown\",\"reason\":\"x\"}]}]}",
            "{\"summary\":\"通过概率很高\",\"weaknesses\":[]}",
            "{\"summary\":\"x\",\"weaknesses\":[{\"tag\":\"系统设计\",\"title\":\"" + "x".repeat(121) + "\",\"diagnosis\":\"b\",\"action\":\"c\",\"evidence\":[{\"questionId\":\"" + a.question() + "\",\"reason\":\"x\"}]}]}"
        }) {
            doReturn(json.readTree(invalid)).when(model).replyJson(anyString());
            mockMvc.perform(post("/api/v1/weaknesses/analysis").with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadGateway());
            mockMvc.perform(get("/api/v1/weaknesses/analysis").with(jwt().jwt(token -> token.subject("user-a"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("稳定分析"));
        }
    }

    @Test void marksSnapshotStaleForQuestionReviewInterviewAndResumeChanges() throws Exception {
        Seed a = seed("user-a", "a", "问题一？");
        insertReview(a.interview(), "review-a", "复盘", a.question());
        doReturn(output("分析", "系统设计", "标题", a.question())).when(model).replyJson(anyString());
        analyze("user-a");
        jdbc.sql("UPDATE interview_questions SET answer_text = '已修改' WHERE id = :id").param("id", a.question()).update();
        stale("user-a");
        analyze("user-a");
        jdbc.sql("INSERT INTO interview_questions (id, interview_id, question_text, answer_text, self_assessment, sort_order) VALUES ('extra-question', :interview, '新增问题？', '回答', 'PARTIAL', 2)").param("interview", a.interview()).update();
        stale("user-a");
        analyze("user-a");
        jdbc.sql("DELETE FROM interview_questions WHERE id = 'extra-question'").update();
        stale("user-a");
        analyze("user-a");
        Seed extraInterview = seed("user-a", "extra", "另一场问题？");
        stale("user-a");
        analyze("user-a");
        jdbc.sql("DELETE FROM interviews WHERE id = :id").param("id", extraInterview.interview()).update();
        stale("user-a");
        analyze("user-a");
        jdbc.sql("UPDATE resume_files SET parsed_status = 'READY', parsed_text = '新的简历正文' WHERE id = :id").param("id", a.resume()).update();
        stale("user-a");
        analyze("user-a");
        jdbc.sql("DELETE FROM review_reports WHERE id = 'review-a'").update();
        stale("user-a");
        mockMvc.perform(get("/api/v1/weaknesses").with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test void tasksKeepExactQuestionAndRejectCrossUserOrMismatchedSources() throws Exception {
        Seed a = seed("user-a", "a", "A 问题？");
        insertReview(a.interview(), "review-a", "复盘", a.question());
        Seed b = seed("user-b", "b", "B 问题？");
        insertReview(b.interview(), "review-b", "复盘", b.question());
        String correct = "{\"title\":\"练习\",\"weaknessTag\":\"系统设计\",\"action\":\"补充边界\",\"status\":\"NOT_STARTED\",\"sourceQuestionId\":\"" + a.question() + "\",\"sourceInterviewId\":\"" + a.interview() + "\",\"sourceReviewReportId\":\"review-a\"}";
        mockMvc.perform(post("/api/v1/training-tasks").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content(correct))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.source.questionId").value(a.question())).andExpect(jsonPath("$.source.questionText").value("A 问题？"));
        mockMvc.perform(post("/api/v1/training-tasks").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON).content(correct)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/training-tasks").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content(correct.replace("review-a", "review-b"))).andExpect(status().isNotFound());
        jdbc.sql("DELETE FROM interviews WHERE id = :id").param("id", a.interview()).update();
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM training_tasks WHERE user_id = 'user-a'").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM training_tasks WHERE source_question_id IS NOT NULL").query(Integer.class).single());
    }

    private void stale(String user) throws Exception {
        mockMvc.perform(get("/api/v1/weaknesses/analysis").with(jwt().jwt(token -> token.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(true)).andExpect(jsonPath("$.items").isEmpty());
    }

    private void analyze(String user) throws Exception {
        mockMvc.perform(post("/api/v1/weaknesses/analysis").with(jwt().jwt(token -> token.subject(user)))).andExpect(status().isOk());
    }

    private JsonNode output(String summary, String tag, String title, String questionId) throws Exception {
        return json.readTree("{\"summary\":\"" + summary + "\",\"weaknesses\":[{\"tag\":\"" + tag + "\",\"title\":\"" + title + "\",\"diagnosis\":\"回答缺少边界。\",\"action\":\"补充验证步骤。\",\"evidence\":[{\"questionId\":\"" + questionId + "\",\"reason\":\"回答没有说明验证。\"}]}]}");
    }

    private Seed seed(String user, String suffix, String text) {
        String resume = "resume-" + suffix;
        String packageId = "package-" + suffix;
        String interview = "interview-" + suffix;
        String question = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO resume_files (id, user_id, original_filename, content_type, size_bytes, object_path, parsed_status, parsed_text) VALUES (:id, :user, 'resume.pdf', 'application/pdf', 1, :path, 'READY', :text)").param("id", resume).param("user", user).param("path", resume).param("text", "简历 " + suffix).update();
        jdbc.sql("INSERT INTO interview_packages (id, user_id, company, role, interview_round, resume_file_id) VALUES (:id, :user, :company, '后端', '技术一面', :resume)").param("id", packageId).param("user", user).param("company", "公司" + suffix).param("resume", resume).update();
        jdbc.sql("INSERT INTO interviews (id, user_id, interview_package_id, company, role, interview_round, interview_time, status, result, interview_type) VALUES (:id, :user, :package, :company, '后端', '技术一面', CURRENT_TIMESTAMP, 'REVIEWED', 'UNKNOWN', 'REAL')").param("id", interview).param("user", user).param("package", packageId).param("company", "公司" + suffix).update();
        jdbc.sql("INSERT INTO interview_questions (id, interview_id, question_text, answer_text, self_assessment, sort_order) VALUES (:id, :interview, :text, '回答', 'PARTIAL', 1)").param("id", question).param("interview", interview).param("text", text).update();
        return new Seed(resume, interview, question);
    }

    private void insertReview(String interview, String report, String summary, String question) {
        jdbc.sql("INSERT INTO review_reports (id, interview_id, readiness, summary, weakness_tags) VALUES (:id, :interview, :readiness, :summary, :tags)")
            .param("id", report).param("interview", interview).param("readiness", "待补充").param("summary", summary).param("tags", "[\"系统设计\"]").update();
        jdbc.sql("INSERT INTO question_reviews (id, review_report_id, interview_question_id, evaluation, answer_evidence, missing_evidence, improvement_action, recommended_answer_structure, possible_followups) VALUES (:id, :report, :question, '待补充', '待补充', '待补充', '补充验证', '结论-依据', '[]')").param("id", UUID.randomUUID().toString()).param("report", report).param("question", question).update();
    }

    private record Seed(String resume, String interview, String question) {}
}

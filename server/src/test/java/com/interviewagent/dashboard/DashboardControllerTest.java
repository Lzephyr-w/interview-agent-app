package com.interviewagent.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import com.interviewagent.ai.ReviewModelClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:dashboard-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class DashboardControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @MockBean ReviewModelClient model;

    @BeforeEach void clearData() {
        jdbc.sql("DELETE FROM sprint_checklist_items").update();
        jdbc.sql("DELETE FROM training_tasks").update();
        jdbc.sql("DELETE FROM mock_interviews").update();
        jdbc.sql("DELETE FROM interviews").update();
        jdbc.sql("DELETE FROM interview_packages").update();
        jdbc.sql("DELETE FROM resume_files").update();
        jdbc.sql("DELETE FROM job_descriptions").update();
    }

    @Test void dashboardUsesOnlyOwnedRealDataAndReturnsSafeEmptyResult() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/dashboard").with(jwt().jwt(token -> token.subject("empty"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.overview.interviewPackageCount").value(0))
            .andExpect(jsonPath("$.recentActivities").isEmpty()).andExpect(jsonPath("$.sprintItems").isEmpty());

        seed("user-a", "a", "REAL", "PENDING_REVIEW", "[\"系统设计\"]");
        seed("user-b", "b", "MOCK", "PENDING_REVIEW", "[\"项目深挖\"]");
        jdbc.sql("INSERT INTO training_tasks (id, user_id, title, weakness_tag, action, status) VALUES ('task-a', 'user-a', '练习架构', '系统设计', '做一次容量估算。', 'NOT_STARTED')").update();

        mockMvc.perform(get("/api/v1/dashboard").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.overview.interviewPackageCount").value(1))
            .andExpect(jsonPath("$.overview.resumeFileCount").value(1))
            .andExpect(jsonPath("$.overview.pendingReviewCount").value(1))
            .andExpect(jsonPath("$.overview.pendingTrainingTaskCount").value(1))
            .andExpect(jsonPath("$.weaknesses").isEmpty())
            .andExpect(jsonPath("$.recentActivities[0].id").value("report-a"))
            .andExpect(jsonPath("$.recentActivities[0].title").value("公司a · 后端"))
            .andExpect(jsonPath("$.sprintItems[?(@.kind == 'TRAINING_TASK')].title").value(org.hamcrest.Matchers.hasItem("练习架构")));
        verifyNoInteractions(model);
    }

    @Test void manualSprintItemIsOwnedAndNeverChangesItsSourceData() throws Exception {
        seed("user-a", "a", "REAL", "PENDING_REVIEW", "[]");
        jdbc.sql("INSERT INTO training_tasks (id, user_id, title, weakness_tag, action, status) VALUES ('task-a', 'user-a', '原训练任务', '系统设计', '原动作', 'NOT_STARTED')").update();
        String body = "{\"title\":\"补充项目指标\",\"description\":\"先列出一组真实数据\",\"targetPath\":\"/library\",\"priority\":90,\"status\":\"TODO\"}";
        String itemId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mockMvc.perform(post("/api/v1/sprint-checklist-items").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.editable").value(true)).andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/api/v1/sprint-checklist-items/{id}", itemId).with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON).content(body.replace("TODO", "DONE"))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/sprint-checklist-items/{id}", itemId).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content(body.replace("TODO", "DONE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        org.junit.jupiter.api.Assertions.assertEquals("NOT_STARTED", jdbc.sql("SELECT status FROM training_tasks WHERE id = 'task-a'").query(String.class).single());
        mockMvc.perform(delete("/api/v1/sprint-checklist-items/{id}", itemId).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id = 'user-a'").query(Integer.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM training_tasks WHERE id = 'task-a'").query(Integer.class).single());
        mockMvc.perform(delete("/api/v1/sprint-checklist-items/{id}", itemId).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/sprint-checklist-items").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }

    private void seed(String user, String suffix, String type, String status, String tags) {
        jdbc.sql("INSERT INTO resume_files (id, user_id, original_filename, content_type, size_bytes, object_path) VALUES (:id, :user, 'resume.pdf', 'application/pdf', 8, :path)").param("id", "file-" + suffix).param("user", user).param("path", "resumes/" + suffix).update();
        jdbc.sql("INSERT INTO job_descriptions (id, user_id, company, role, content) VALUES (:id, :user, :company, '后端', 'Spring')").param("id", "jd-" + suffix).param("user", user).param("company", "公司" + suffix).update();
        jdbc.sql("INSERT INTO interview_packages (id, user_id, company, role, interview_round, resume_file_id, job_description_id) VALUES (:id, :user, :company, '后端', '技术一面', :file, :jd)").param("id", "package-" + suffix).param("user", user).param("company", "公司" + suffix).param("file", "file-" + suffix).param("jd", "jd-" + suffix).update();
        jdbc.sql("INSERT INTO interviews (id, user_id, interview_package_id, company, role, interview_round, interview_time, status, result, interview_type) VALUES (:id, :user, :package, :company, '后端', '技术一面', CURRENT_TIMESTAMP, :status, 'UNKNOWN', :type)").param("id", "interview-" + suffix).param("user", user).param("package", "package-" + suffix).param("company", "公司" + suffix).param("status", status).param("type", type).update();
        jdbc.sql("INSERT INTO review_reports (id, interview_id, readiness, summary, weakness_tags) VALUES (:id, :interview, '待补充', '待补充', :tags)").param("id", "report-" + suffix).param("interview", "interview-" + suffix).param("tags", tags).update();
    }
}

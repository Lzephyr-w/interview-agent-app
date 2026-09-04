package com.interviewagent.material;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:material-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class MaterialControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @MockBean ResumeFileStorage storage;

    @Test void acceptsOnlyOwnedResumeFilesForPackages() throws Exception {
        String resumeFileId = resumeFile("user-a");
        String jdId = id(create("/api/v1/job-descriptions", "user-a", "{\"company\":\"A 公司\",\"role\":\"后端工程师\",\"content\":\"Spring Boot\"}"));
        String cardId = id(create("/api/v1/evidence-cards", "user-a", "{\"projectName\":\"订单系统\",\"technologyStack\":\"Spring Boot、MySQL\",\"projectDescriptionAndResponsibilities\":\"负责后端开发\",\"projectHighlights\":\"降低延迟；兼顾一致性\"}"));

        mockMvc.perform(post("/api/v1/interview-packages").with(jwt().jwt(token -> token.subject("user-b")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"company\":\"A 公司\",\"role\":\"后端工程师\",\"interviewRound\":\"技术一面\",\"resumeFileId\":\"" + resumeFileId + "\",\"jobDescriptionId\":\"" + jdId + "\",\"evidenceCardIds\":[\"" + cardId + "\"]}"))
            .andExpect(status().isNotFound());

        id(create("/api/v1/interview-packages", "user-a", "{\"company\":\"A 公司\",\"role\":\"后端工程师\",\"interviewRound\":\"技术一面\",\"resumeFileId\":\"" + resumeFileId + "\",\"jobDescriptionId\":\"" + jdId + "\",\"evidenceCardIds\":[\"" + cardId + "\"]}"));
        mockMvc.perform(get("/api/v1/interview-packages").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].resumeId").doesNotExist()).andExpect(jsonPath("$[0].resumeFileId").value(resumeFileId)).andExpect(jsonPath("$[0].evidenceCardIds[0]").value(cardId));
    }

    @Test void evidenceCardCrudUsesFourCoreFieldsAndValidatesThem() throws Exception {
        String body = "{\"projectName\":\"支付平台\",\"technologyStack\":\"React、TypeScript\",\"projectDescriptionAndResponsibilities\":\"负责支付页面与接口\",\"projectHighlights\":\"首屏提速 30%\\n补齐异常兜底\"}";
        String id = id(create("/api/v1/evidence-cards", "crud-user", body));
        mockMvc.perform(get("/api/v1/evidence-cards/{id}", id).with(jwt().jwt(token -> token.subject("crud-user"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectName").value("支付平台"))
            .andExpect(jsonPath("$.technologyStack").value("React、TypeScript"))
            .andExpect(jsonPath("$.projectDescriptionAndResponsibilities").value("负责支付页面与接口"))
            .andExpect(jsonPath("$.projectHighlights").value("首屏提速 30%\n补齐异常兜底"))
            .andExpect(jsonPath("$.backgroundAndRole").doesNotExist())
            .andExpect(jsonPath("$.applicableQuestionTypes").doesNotExist());

        String updated = "{\"projectName\":\"支付平台 2.0\",\"technologyStack\":\"React、TypeScript、Spring Boot\",\"projectDescriptionAndResponsibilities\":\"负责全栈交付\",\"projectHighlights\":\"完成灰度发布\"}";
        mockMvc.perform(put("/api/v1/evidence-cards/{id}", id).with(jwt().jwt(token -> token.subject("crud-user")))
            .contentType(MediaType.APPLICATION_JSON).content(updated))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectName").value("支付平台 2.0"));
        mockMvc.perform(post("/api/v1/evidence-cards").with(jwt().jwt(token -> token.subject("crud-user")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"projectName\":\"缺字段\",\"technologyStack\":\"Java\",\"projectDescriptionAndResponsibilities\":\"描述\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void migratedLegacyColumnsRemainReadableThroughNewFields() throws Exception {
        String legacyId = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO project_evidence_cards (id, user_id, project_name, background_and_role, personal_contribution, goal_and_metrics, constraints_and_tradeoffs, result_and_retrospective, applicable_question_types, technology_stack, project_description_and_responsibilities, project_highlights) VALUES (:id, 'legacy-user', '旧项目', '背景', '贡献', '目标', '取舍', '结果', '项目深挖', '待补充', '背景；贡献', '目标；取舍；结果')")
            .param("id", legacyId).update();
        mockMvc.perform(get("/api/v1/evidence-cards/{id}", legacyId).with(jwt().jwt(token -> token.subject("legacy-user"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectName").value("旧项目"))
            .andExpect(jsonPath("$.projectDescriptionAndResponsibilities").value("背景；贡献"))
            .andExpect(jsonPath("$.projectHighlights").value("目标；取舍；结果"))
            .andExpect(jsonPath("$.technologyStack").value("待补充"));
    }

    @Test void acceptsOnlyOwnedVerifiedResumeFiles() throws Exception {
        byte[] pdf = "%PDF-1.7\nresume".getBytes();
        MockMultipartFile validFile = new MockMultipartFile("file", "resume.pdf", "text/plain", pdf);
        String fileId = id(mockMvc.perform(multipart("/api/v1/resume-files").file(validFile).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.contentType").value("application/pdf"))
            .andExpect(jsonPath("$.parseStatus").value("FAILED")).andReturn());
        String jdId = id(create("/api/v1/job-descriptions", "user-a", "{\"company\":\"A 公司\",\"role\":\"后端工程师\",\"content\":\"Spring Boot\"}"));
        String packageId = id(mockMvc.perform(post("/api/v1/interview-packages").with(jwt().jwt(token -> token.subject("user-a")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"company\":\"A 公司\",\"role\":\"后端工程师\",\"interviewRound\":\"技术一面\",\"resumeFileId\":\"" + fileId + "\",\"jobDescriptionId\":\"" + jdId + "\",\"evidenceCardIds\":[]}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.resumeFileId").value(fileId)).andReturn());

        mockMvc.perform(get("/api/v1/resume-files/{id}", fileId).with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/resume-files/{id}/content", fileId).with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/resume-files/{id}", fileId).with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isNotFound());
        verify(storage, never()).download(anyString());
        verify(storage, never()).delete(anyString());

        when(storage.download(anyString())).thenReturn(pdf);
        mockMvc.perform(get("/api/v1/resume-files/{id}/content", fileId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(pdf));
        mockMvc.perform(delete("/api/v1/resume-files/{id}", fileId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isNoContent());
        verify(storage).delete(anyString());
        mockMvc.perform(get("/api/v1/interview-packages/{id}", packageId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resumeFileId").doesNotExist());

        MockMultipartFile disguisedPdf = new MockMultipartFile("file", "resume.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", pdf);
        mockMvc.perform(multipart("/api/v1/resume-files").file(disguisedPdf).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isBadRequest());
    }

    @Test void missingStorageConfigurationReturnsSafeStableError() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("服务器未配置 Supabase Storage 访问凭据。"))
            .when(storage).upload(anyString(), anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
        MockMultipartFile pdf = new MockMultipartFile("file", "resume.pdf", "application/pdf", "%PDF-1.7\nresume".getBytes());
        mockMvc.perform(multipart("/api/v1/resume-files").file(pdf).with(jwt().jwt(token -> token.subject("config-user"))))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("服务器未配置 Supabase Storage 访问凭据。"));
    }

    private MvcResult create(String path, String userId, String body) throws Exception {
        return mockMvc.perform(post(path).with(jwt().jwt(token -> token.subject(userId))).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
    }

    private String id(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private String resumeFile(String user) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO resume_files (id, user_id, original_filename, content_type, size_bytes, object_path) VALUES (:id, :userId, 'resume.pdf', 'application/pdf', 8, :path)")
            .param("id", id).param("userId", user).param("path", "resumes/" + id + ".pdf").update();
        return id;
    }
}

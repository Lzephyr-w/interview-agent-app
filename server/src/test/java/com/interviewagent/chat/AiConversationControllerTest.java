package com.interviewagent.chat;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.interview.ReviewFailedException;
import com.interviewagent.material.ResumeFileService;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "SUPABASE_URL=https://example.supabase.co",
    "spring.datasource.url=jdbc:h2:mem:chat-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.default-schema=PUBLIC",
    "spring.flyway.schemas=PUBLIC",
    "spring.flyway.create-schemas=false"
})
@AutoConfigureMockMvc
class AiConversationControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @MockBean AgentPythonClient agent;
    @SpyBean ResumeFileService resumeFiles;

    @Test
    void persistsMessagesIdempotentlyAndRetriesOneFailedReplyWithoutCrossUserAccess() throws Exception {
        String packageA = packageFor("user-a");
        String packageB = packageFor("user-b");
        String interviewA = interviewFor("user-a", packageA);
        String interviewB = interviewFor("user-b", packageB);
        String otherPackageA = packageFor("user-a");
        String otherInterviewA = interviewFor("user-a", otherPackageA);
        jdbc.sql("INSERT INTO review_reports (id, interview_id, readiness, summary, weakness_tags) VALUES ('review-a', :interview, '准备不足', '指标待补充', '[\"项目深挖\"]')")
            .param("interview", interviewA).update();
        jdbc.sql("INSERT INTO mock_interviews (id, user_id, interview_package_id, company, role, interview_round, status, total_questions) VALUES ('mock-a', 'user-a', :packageId, 'A 公司', '后端', '技术一面', 'RUNNING', 6)")
            .param("packageId", packageA).update();
        jdbc.sql("INSERT INTO training_tasks (id, user_id, title, weakness_tag, action, status, source_interview_id, source_review_report_id) VALUES ('task-a', 'user-a', '补指标', '项目深挖', '补充真实指标', 'NOT_STARTED', :interview, 'review-a')")
            .param("interview", interviewA).update();

        mockMvc.perform(get("/api/v1/ai-conversations")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/ai-conversations").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        String unscoped = id(mockMvc.perform(post("/api/v1/ai-conversations").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.conversation.title").value("AI Agent 对话"))
            .andExpect(jsonPath("$.conversation.contextSources[0].state").value("Agent 可自主查询")).andReturn(), "conversation.id");
        mockMvc.perform(delete("/api/v1/ai-conversations/{id}", unscoped).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/ai-conversations").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewPackageId\":\"" + packageA + "\"}"))
            .andExpect(status().isNotFound());

        String conversation = id(mockMvc.perform(post("/api/v1/ai-conversations").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewPackageId\":\"" + packageA + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.conversation.contextSources[0].state").value("已纳入"))
            .andExpect(jsonPath("$.conversation.contextSources[1].state").value("已纳入"))
            .andReturn(), "conversation.id");
        String resumeA = jdbc.sql("SELECT resume_file_id FROM interview_packages WHERE id = :id").param("id", packageA).query(String.class).single();
        org.mockito.Mockito.reset(resumeFiles);
        mockMvc.perform(get("/api/v1/ai-conversations").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(conversation))
            .andExpect(jsonPath("$[0].contextSources").doesNotExist())
            .andExpect(jsonPath("$[0].messages").doesNotExist());
        verify(resumeFiles, org.mockito.Mockito.never()).parsedText(anyString(), anyString());
        jdbc.sql("UPDATE resume_files SET parsed_status = 'PENDING', parsed_text = NULL WHERE id = :id").param("id", resumeA).update();
        doReturn(new ResumeFileService.ParsedResume("候选人具有 Java 与 Spring Boot 项目经验。", "READY", false, null))
            .when(resumeFiles).parsedText("user-a", resumeA);
        mockMvc.perform(get("/api/v1/ai-conversations/{id}", conversation).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversation.contextSources[1].state").value("已纳入"));
        verify(resumeFiles).parsedText("user-a", resumeA);
        mockMvc.perform(post("/api/v1/ai-conversations").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewPackageId\":\"" + packageB + "\",\"interviewId\":\"" + interviewA + "\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/ai-conversations").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewPackageId\":\"" + packageB + "\",\"interviewId\":\"" + interviewB + "\",\"reviewReportId\":\"review-a\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/ai-conversations").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewPackageId\":\"" + packageA + "\",\"interviewId\":\"" + otherInterviewA + "\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("关联面试必须属于所选面试包。"));
        mockMvc.perform(get("/api/v1/ai-conversations/{id}", conversation).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());

        String request = "request-" + UUID.randomUUID();
        MvcResult saved = mockMvc.perform(post("/api/v1/ai-conversations/{id}/messages", conversation).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"我的指标资料不足怎么办？\",\"clientRequestId\":\"" + request + "\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.messages.length()").value(1)).andReturn();
        String question = id(saved, "messages[0].id");
        mockMvc.perform(post("/api/v1/ai-conversations/{id}/messages", conversation).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"重复点击不应新建问题\",\"clientRequestId\":\"" + request + "\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.messages.length()").value(1));
        mockMvc.perform(post("/api/v1/ai-conversations/{id}/messages/{messageId}/reply", conversation, question).with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isNotFound());

        when(agent.reply(anyString(), anyString(), anyList(), anyString()))
            .thenThrow(new ReviewFailedException("AI 服务尚未配置，请联系管理员后重试。"))
            .thenThrow(new IllegalStateException("provider details"))
            .thenReturn("现有资料未提供指标，待补充具体量化结果后再组织回答。");
        mockMvc.perform(post("/api/v1/ai-conversations/{id}/messages/{messageId}/reply", conversation, question).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ASSISTANT"))
            .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.content").value(""))
            .andExpect(jsonPath("$.replyToMessageId").value(question)).andExpect(jsonPath("$.messages").doesNotExist());
        mockMvc.perform(post("/api/v1/ai-conversations/{id}/messages/{messageId}/reply", conversation, question).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.errorMessage").value("AI 回复失败，请重试。"));
        mockMvc.perform(post("/api/v1/ai-conversations/{id}/messages/{messageId}/reply", conversation, question).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ASSISTANT"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.replyToMessageId").value(question)).andExpect(jsonPath("$.messages").doesNotExist());
        mockMvc.perform(post("/api/v1/ai-conversations/{id}/messages/{messageId}/reply", conversation, question).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.messages").doesNotExist());
        for (int index = 0; index < 13; index++) {
            jdbc.sql("INSERT INTO ai_conversation_messages (id, conversation_id, role, content, status, client_request_id) VALUES (:id, :conversationId, 'USER', :content, 'SAVED', :requestId)")
                .param("id", "history-" + index).param("conversationId", conversation).param("content", "历史消息-" + index).param("requestId", "history-request-" + index).update();
        }
        mockMvc.perform(delete("/api/v1/ai-conversations/{id}", conversation).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/ai-conversations/{id}", conversation).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isNoContent());
        Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM interview_packages WHERE id = :id").param("id", packageA).query(Integer.class).single());
        Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM interviews WHERE id = :id").param("id", interviewA).query(Integer.class).single());
        Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE id = 'review-a'").query(Integer.class).single());
        Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM mock_interviews WHERE id = 'mock-a'").query(Integer.class).single());
        Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM training_tasks WHERE id = 'task-a'").query(Integer.class).single());
        Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_conversation_messages WHERE conversation_id = :id").param("id", conversation).query(Integer.class).single());
    }

    private String packageFor(String user) throws Exception {
        String resumeFile = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO resume_files (id, user_id, original_filename, content_type, size_bytes, object_path, parsed_text, parsed_status) VALUES (:id, :userId, 'resume.pdf', 'application/pdf', 8, :path, '候选人具有 Java 与 Spring Boot 项目经验。', 'READY')")
            .param("id", resumeFile).param("userId", user).param("path", "resumes/" + resumeFile + ".pdf").update();
        String jd = id(mockMvc.perform(post("/api/v1/job-descriptions").with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"A 公司\",\"role\":\"后端\",\"content\":\"Spring Boot 与缓存\"}"))
            .andExpect(status().isCreated()).andReturn(), "id");
        return id(mockMvc.perform(post("/api/v1/interview-packages").with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"A 公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"resumeFileId\":\"" + resumeFile + "\",\"jobDescriptionId\":\"" + jd + "\",\"evidenceCardIds\":[]}"))
            .andExpect(status().isCreated()).andReturn(), "id");
    }

    private String interviewFor(String user, String packageId) throws Exception {
        return id(mockMvc.perform(post("/api/v1/interviews").with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"A 公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"interviewTime\":\"2026-08-08T10:00:00+08:00\",\"interviewPackageId\":\"" + packageId + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"}"))
            .andExpect(status().isCreated()).andReturn(), "interview.id");
    }

    private String id(MvcResult result, String path) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        for (String part : path.split("\\.")) {
            if (part.contains("[")) node = node.path(part.substring(0, part.indexOf('['))).path(Integer.parseInt(part.substring(part.indexOf('[') + 1, part.length() - 1)));
            else node = node.path(part);
        }
        return node.asText();
    }
}

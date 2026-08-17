package com.interviewagent.ai;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.interviewagent.interview.ReviewFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "SUPABASE_URL=https://example.supabase.co",
    "spring.datasource.url=jdbc:h2:mem:agent-unavailable-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.default-schema=PUBLIC",
    "spring.flyway.schemas=PUBLIC",
    "spring.flyway.create-schemas=false"
})
@AutoConfigureMockMvc
class AgentUnavailableControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @MockBean AgentPythonClient agent;

    @Test
    void savesReadableFailureWhenAgentIsUnavailable() throws Exception {
        jdbc.sql("INSERT INTO ai_conversations(id,user_id,title) VALUES ('conversation-a','user-a','Agent')").update();
        jdbc.sql("INSERT INTO ai_conversation_messages(id,conversation_id,role,content,status,client_request_id) VALUES ('message-a','conversation-a','USER','问题','SAVED','request-a')").update();
        when(agent.reply(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new ReviewFailedException("Agent 服务暂时不可用，请稍后重试。"));
        mockMvc.perform(post("/api/v1/ai-conversations/conversation-a/messages/message-a/reply").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.errorMessage").value("Agent 服务暂时不可用，请稍后重试。"));
    }
}

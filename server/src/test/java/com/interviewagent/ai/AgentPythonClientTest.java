package com.interviewagent.ai;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.interview.ReviewFailedException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPythonClientTest {
    @Test
    void reportsMissingAgentConfiguration() {
        ReviewFailedException error = assertThrows(ReviewFailedException.class, () -> new AgentPythonClient(new ObjectMapper(), "", "").reply("user-a", "conversation-a", List.of(), "context"));
        assertTrue(error.getMessage().contains("Agent 服务尚未配置"));
    }

    @Test
    void reportsJavaPythonRequestFailure() {
        ReviewFailedException error = assertThrows(ReviewFailedException.class, () -> new AgentPythonClient(new ObjectMapper(), "http://127.0.0.1:1", "secret").reply("user-a", "conversation-a", List.of(Map.of("role", "user", "content", "hi")), "context"));
        assertTrue(error.getMessage().contains("Agent 服务暂时不可用"));
    }
}

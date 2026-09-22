package com.interviewagent.ai;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.interview.ReviewFailedException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

    @Test
    void completesOnSseDoneWithoutWaitingForConnectionClose() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/reply/stream", exchange -> {
            byte[] response = "event: delta\ndata: {\"content\":\"完成\"}\n\nevent: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().flush();
            try { Thread.sleep(1_000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var deltas = new ArrayList<String>();
            Instant started = Instant.now();
            new AgentPythonClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "secret")
                .replyStream("user-a", "conversation-a", List.of(Map.of("role", "user", "content", "hi")), "", deltas::add);
            assertEquals(List.of("完成"), deltas);
            assertTrue(Duration.between(started, Instant.now()).toMillis() < 800);
        } finally { server.stop(0); }
    }
}

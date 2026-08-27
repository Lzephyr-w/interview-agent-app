package com.interviewagent.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReviewModelClientTest {
    @Test
    void readsJsonFromReasoningContentWhenContentIsEmpty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"{\\\"ok\\\":true}\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertTrue(new ReviewModelClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "key", "model").review("test").path("ok").asBoolean());
        } finally {
            server.stop(0);
        }
    }
}

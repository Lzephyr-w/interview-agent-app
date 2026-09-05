package com.interviewagent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationClientTest {
    final ObjectMapper json = new ObjectMapper();
    final Map<String,Object> input = Map.of("materials", Map.of("company","甲","role","开发","round","一面","jd","待补充","resume","待补充","cards",java.util.List.of()), "history", java.util.List.of());

    @Test void validatesResponseEnvelopeAndStableErrors() throws Exception {
        for (String mode : new String[]{"ok", "version", "requestId", "shape", "error"}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/agent/simulations", exchange -> {
                var request = json.readTree(exchange.getRequestBody());
                assertEquals("simulation.v1", request.path("version").asText());
                assertEquals("TEXT_MAIN_QUESTION", request.path("operation").asText());
                assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-Agent-Key"));
                assertTrue(request.path("deadlineAtEpochMs").asLong() <= System.currentTimeMillis()+70000);
                var response = json.createObjectNode().put("version", mode.equals("version") ? "v2" : "simulation.v1")
                    .put("requestId", mode.equals("requestId") ? java.util.UUID.randomUUID().toString() : request.path("requestId").asText());
                if (mode.equals("error")) response.putObject("error").put("code","MODEL_UNAVAILABLE").put("message","供应商原始秘密").put("retryable",true);
                else if (mode.equals("shape")) response.putObject("result").put("questionText",123);
                else response.putObject("result").put("questionText","如何验证？");
                byte[] bytes = json.writeValueAsBytes(response);
                exchange.sendResponseHeaders(mode.equals("error") ? 502 : 200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            try {
                var client = new AgentPythonClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"test-key");
                if (mode.equals("ok")) assertEquals("如何验证？",client.simulate("TEXT_MAIN_QUESTION",input).path("questionText").asText());
                else {
                    var error = assertThrows(SimulationException.class, () -> client.simulate("TEXT_MAIN_QUESTION",input));
                    assertEquals(mode.equals("error"),error.retryable());
                    assertFalse(error.getMessage().contains("秘密"));
                }
            } finally { server.stop(0); }
        }
    }

    @Test void expiredDeadlineDoesNotCallProvider() {
        var client = new AgentPythonClient(json,"http://127.0.0.1:1","test-key");
        assertEquals("INVALID_REQUEST",assertThrows(SimulationException.class,()->client.simulate(null,input)).code());
        assertEquals("MODEL_TIMEOUT", assertThrows(SimulationException.class,
            () -> client.simulate("TEXT_MAIN_QUESTION",input,System.currentTimeMillis()-1)).code());
    }

    @Test void httpTimeoutHonorsRemainingBudget() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/agent/simulations",exchange->{
            try { Thread.sleep(300); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var client=new AgentPythonClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"test-key");
            assertEquals("MODEL_TIMEOUT",assertThrows(SimulationException.class,()->client.simulate("TEXT_MAIN_QUESTION",input,System.currentTimeMillis()+80)).code());
        } finally { server.stop(0); }
    }
}

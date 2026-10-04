package com.interviewagent.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReviewModelClientTest {
    @Test void structuredReviewSendsNativeSchemaAndRefusesUnsupportedGatewayWithoutRetry() throws Exception {
        var json = new ObjectMapper();
        var requests = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        var schema = java.util.Map.<String, Object>of("type", "object", "properties", java.util.Map.of("ok", java.util.Map.of("type", "boolean")),
            "required", java.util.List.of("ok"), "additionalProperties", false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new ReviewModelClient(json, "http://127.0.0.1:" + server.getAddress().getPort(), "key", "model");
            assertTrue(client.review("JSON test", 10, schema).path("ok").asBoolean());
            var format = requests.getFirst().path("response_format");
            org.junit.jupiter.api.Assertions.assertEquals("json_schema", format.path("type").asText());
            assertTrue(format.path("json_schema").path("strict").asBoolean());
            org.junit.jupiter.api.Assertions.assertEquals(json.valueToTree(schema), format.path("json_schema").path("schema"));
            org.junit.jupiter.api.Assertions.assertEquals(8192, requests.getFirst().path("max_tokens").asInt());
            client.importJson("JSON test", 10);
            org.junit.jupiter.api.Assertions.assertEquals("json_object", requests.get(1).path("response_format").path("type").asText());
            status.set(400);
            var failure = org.junit.jupiter.api.Assertions.assertThrows(com.interviewagent.interview.ReviewFailedException.class, () -> client.review("JSON test", 10, schema));
            org.junit.jupiter.api.Assertions.assertEquals("HTTP_400", failure.code());
            assertTrue(failure.getMessage().contains("结构化输出请求被拒绝"));
            org.junit.jupiter.api.Assertions.assertEquals(3, requests.size());
        } finally { server.stop(0); }
    }

    @Test void reviewRequiresCompleteJsonAndReportsSafeParseLocations() throws Exception {
        var json = new ObjectMapper();
        var http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> response = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (name, value) -> true));
        org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class), org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var client = new ReviewModelClient(json, "http://localhost", "key", "model");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "http", http);
        String valid = "{\"ok\":true,\"summary\":\"第一段\\n\\n第二段\",\"quote\":\"他说\\\"你好\\\"\"}";
        for (String content : java.util.List.of(valid, "```json\n" + valid + "\n```")) {
            org.mockito.Mockito.when(response.body()).thenReturn(json.writeValueAsString(java.util.Map.of("choices", java.util.List.of(java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of("content", content))))));
            org.junit.jupiter.api.Assertions.assertEquals("第一段\n\n第二段", client.review("test", 10).path("summary").asText());
        }
        String privateText = "不能出现在错误提示里的私密回答";
        for (String content : java.util.List.of("{\"private\":\"" + privateText + "\"", "{\"items\":[{\"ok\":true}]",
                "{\"summary\":\"" + privateText + "\n第二段\"}", "解释\n" + valid, valid + "\n解释", valid + "\n{}", "[" + valid + "]")) {
            org.mockito.Mockito.when(response.body()).thenReturn(json.writeValueAsString(java.util.Map.of("choices", java.util.List.of(java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of("content", content))))));
            var failure = org.junit.jupiter.api.Assertions.assertThrows(com.interviewagent.interview.ReviewFailedException.class, () -> client.review("test", 10));
            org.junit.jupiter.api.Assertions.assertEquals("INVALID_JSON", failure.code());
            org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains(privateText));
            if (failure.getCause() instanceof com.fasterxml.jackson.core.io.JsonEOFException) {
                assertTrue(failure.getMessage().contains("未完整闭合"));
                assertTrue(failure.getMessage().contains("行，第"));
            }
        }
        // The stricter review parser must not change existing import/weakness JSON extraction.
        org.mockito.Mockito.when(response.body()).thenReturn(json.writeValueAsString(java.util.Map.of("choices", java.util.List.of(java.util.Map.of("message", java.util.Map.of("content", "解释\n" + valid + "\n结束"))))));
        assertTrue(client.importJson("test", 10).path("ok").asBoolean());
        assertTrue(client.replyJson("test").path("ok").asBoolean());
        org.mockito.Mockito.when(response.body()).thenReturn(json.writeValueAsString(java.util.Map.of("choices", java.util.List.of(java.util.Map.of("finish_reason", "length", "message", java.util.Map.of("content", valid))))));
        var truncated = org.junit.jupiter.api.Assertions.assertThrows(com.interviewagent.interview.ReviewFailedException.class, () -> client.review("test", 10));
        org.junit.jupiter.api.Assertions.assertEquals("TRUNCATED", truncated.code());
    }

    @Test void reviewUsesConfigurableTimeoutAndImportKeepsItsOwnTimeout() throws Exception {
        var http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> response = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (name, value) -> true));
        org.mockito.Mockito.when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}");
        org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class), org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var client = new ReviewModelClient(new ObjectMapper(), "http://localhost", "key", "model");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "http", http);
        client.review("test");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "reviewTimeoutSeconds", 120);
        client.review("test");
        client.review("test", 17);
        client.review("test", 899);
        client.importJson("test", 7);
        client.replyJson("test");
        var requests = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(6)).send(requests.capture(), org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(240L, 120L, 17L, 120L, 7L, 60L), requests.getAllValues().stream().map(request -> request.timeout().orElseThrow().toSeconds()).toList());
    }

    @Test void classifiesHttpEnvelopeContentJsonAndTruncation() throws Exception {
        java.util.Map<Integer,String> codes=java.util.Map.of(401,"AUTHENTICATION",403,"AUTHENTICATION",429,"HTTP_429",503,"HTTP_5XX");
        for(var entry:codes.entrySet()) assertFailure(entry.getKey(),"{}",entry.getValue(),false);
        assertFailure(200,"not json","PROVIDER_JSON",true);
        assertFailure(200,"{\"choices\":[{\"message\":{}}]}","CONTENT_MISSING",false);
        assertFailure(200,"{\"choices\":[{\"message\":{\"content\":\"{bad}\"}}]}","INVALID_JSON",true);
        assertFailure(200,"{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}","TRUNCATED",false);
    }
    private void assertFailure(int status,String response,String code,boolean cause) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/",exchange->{ byte[] body=response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); exchange.close(); }); server.start();
        try {
            var e=org.junit.jupiter.api.Assertions.assertThrows(com.interviewagent.interview.ReviewFailedException.class,()->new ReviewModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"key","model").importJson("test",2));
            org.junit.jupiter.api.Assertions.assertEquals(code,e.code());
            if(cause) org.junit.jupiter.api.Assertions.assertNotNull(e.getCause());
        } finally { server.stop(0); }
    }
    @Test void distinguishesTimeoutAndConnectionFailure() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/",exchange->{try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();} exchange.close();}); server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            var e=org.junit.jupiter.api.Assertions.assertThrows(com.interviewagent.interview.ReviewFailedException.class,()->new ReviewModelClient(new ObjectMapper(),url,"key","model").importJson("test",1));
            org.junit.jupiter.api.Assertions.assertEquals("HTTP_TIMEOUT",e.code()); org.junit.jupiter.api.Assertions.assertNotNull(e.getCause());
        } finally { server.stop(0); }
        var e=org.junit.jupiter.api.Assertions.assertThrows(com.interviewagent.interview.ReviewFailedException.class,()->new ReviewModelClient(new ObjectMapper(),url,"key","model").importJson("test",1));
        org.junit.jupiter.api.Assertions.assertEquals("CONNECTION",e.code());
    }
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

    @Test
    void sendsAnOutputBudgetForJsonRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"max_tokens\":8192"));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
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

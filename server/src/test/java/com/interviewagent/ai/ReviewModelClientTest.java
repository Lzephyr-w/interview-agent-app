package com.interviewagent.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReviewModelClientTest {
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
        client.importJson("test", 7);
        var requests = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(3)).send(requests.capture(), org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(240L, 120L, 7L), requests.getAllValues().stream().map(request -> request.timeout().orElseThrow().toSeconds()).toList());
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

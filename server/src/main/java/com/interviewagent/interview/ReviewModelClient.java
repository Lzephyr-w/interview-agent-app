package com.interviewagent.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReviewModelClient {
    private final ObjectMapper json;
    private final String url;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    ReviewModelClient(ObjectMapper json, @Value("${app.review-model.url:}") String url, @Value("${app.review-model.api-key:}") String apiKey, @Value("${app.review-model.model:}") String model) {
        this.json = json; this.url = url; this.apiKey = apiKey; this.model = model;
    }

    JsonNode review(String prompt) {
        if (url.isBlank() || apiKey.isBlank() || model.isBlank()) throw new ReviewFailedException("AI 复盘服务尚未配置，请联系管理员后重试。");
        try {
            String body = json.writeValueAsString(java.util.Map.of("model", model, "temperature", 0.2, "response_format", java.util.Map.of("type", "json_object"), "messages", java.util.List.of(java.util.Map.of("role", "user", "content", prompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ReviewFailedException("AI 复盘服务请求失败，请稍后重试。");
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) throw new ReviewFailedException("AI 返回格式无效，请重新发起复盘。");
            return json.readTree(content.asText());
        } catch (ReviewFailedException exception) { throw exception;
        } catch (Exception exception) { throw new ReviewFailedException("AI 复盘超时或返回格式无效，请重新发起复盘。"); }
    }

    public String reply(String prompt) {
        if (url.isBlank() || apiKey.isBlank() || model.isBlank()) throw new ReviewFailedException("AI 服务尚未配置，请联系管理员后重试。");
        try {
            String body = json.writeValueAsString(java.util.Map.of("model", model, "temperature", 0.2, "messages", java.util.List.of(java.util.Map.of("role", "user", "content", prompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ReviewFailedException("AI 服务请求失败，请稍后重试。");
            String content = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) throw new ReviewFailedException("AI 返回格式无效，请重试。");
            return content;
        } catch (ReviewFailedException exception) { throw exception;
        } catch (Exception exception) { throw new ReviewFailedException("AI 服务超时或返回格式无效，请重试。"); }
    }

    public JsonNode replyJson(String prompt) {
        if (url.isBlank() || apiKey.isBlank() || model.isBlank()) throw new ReviewFailedException("AI 服务尚未配置，请联系管理员后重试。");
        try {
            String body = json.writeValueAsString(Map.of("model", model, "temperature", 0.2, "response_format", Map.of("type", "json_object"), "messages", List.of(Map.of("role", "user", "content", prompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ReviewFailedException("AI 服务请求失败，请稍后重试。");
            String content = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) throw new ReviewFailedException("AI 返回格式无效，请重试。");
            return json.readTree(content);
        } catch (ReviewFailedException exception) { throw exception;
        } catch (Exception exception) { throw new ReviewFailedException("AI 服务超时或返回格式无效，请重试。"); }
    }

    public JsonNode agent(List<Map<String, Object>> messages, JsonNode tools) {
        if (url.isBlank() || apiKey.isBlank() || model.isBlank()) throw new ReviewFailedException("AI 服务尚未配置，请联系管理员后重试。");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.2);
            body.put("messages", messages);
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ReviewFailedException("AI Agent 请求失败，请稍后重试。");
            JsonNode message = json.readTree(response.body()).path("choices").path(0).path("message");
            if (!message.isObject() || ((!message.path("content").isTextual() || message.path("content").asText().isBlank()) && !message.path("tool_calls").isArray())) throw new ReviewFailedException("AI Agent 返回格式无效，请重试。");
            return message;
        } catch (ReviewFailedException exception) { throw exception;
        } catch (Exception exception) { throw new ReviewFailedException("AI Agent 超时或返回格式无效，请重试。"); }
    }
}

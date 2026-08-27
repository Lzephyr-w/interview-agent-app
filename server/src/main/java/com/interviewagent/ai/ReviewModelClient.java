package com.interviewagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.interview.ReviewFailedException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared model gateway for review and simulation flows. Chat Agent calls Python instead. */
@Component
public class ReviewModelClient {
    private final ObjectMapper json;
    private final String url;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ReviewModelClient(ObjectMapper json, @Value("${app.review-model.url:}") String url, @Value("${app.review-model.api-key:}") String apiKey, @Value("${app.review-model.model:}") String model) {
        this.json = json; this.url = url; this.apiKey = apiKey; this.model = model;
    }

    public JsonNode review(String prompt) {
        return jsonReply(prompt, "AI 复盘");
    }

    public String reply(String prompt) {
        JsonNode response = request(Map.of("model", model, "temperature", 0.2, "messages", List.of(Map.of("role", "user", "content", prompt))));
        String content = content(response);
        if (content.isBlank()) throw new ReviewFailedException("AI 返回格式无效，请重试。");
        return content;
    }

    public JsonNode replyJson(String prompt) {
        return jsonReply(prompt, "AI");
    }

    private JsonNode jsonReply(String prompt, String label) {
        JsonNode response = request(Map.of("model", model, "temperature", 0.2, "response_format", Map.of("type", "json_object"), "messages", List.of(Map.of("role", "user", "content", prompt))));
        String content = content(response);
        if (content.isBlank()) throw new ReviewFailedException(label + "返回格式无效，请重试。");
        try { return json.readTree(jsonText(content)); }
        catch (Exception exception) { throw new ReviewFailedException(label + "返回格式无效，请重试。"); }
    }

    private JsonNode request(Map<String, Object> body) {
        if (url.isBlank() || apiKey.isBlank() || model.isBlank()) throw new ReviewFailedException("AI 服务尚未配置，请联系管理员后重试。");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ReviewFailedException("AI 服务请求失败，请稍后重试。");
            return json.readTree(response.body());
        } catch (ReviewFailedException exception) { throw exception;
        } catch (Exception exception) { throw new ReviewFailedException("AI 服务超时或返回格式无效，请重试。"); }
    }

    private static String content(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        JsonNode value = message.path("content");
        if (value.isTextual() && !value.asText().isBlank()) return value.asText().trim();
        if (value.isArray()) { StringBuilder result = new StringBuilder(); for (JsonNode part : value) result.append(part.path("text").asText(part.asText(""))); if (!result.isEmpty()) return result.toString().trim(); }
        return message.path("reasoning_content").asText("").trim();
    }

    private static String jsonText(String value) {
        String clean = value.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        int start = clean.indexOf('{'), end = clean.lastIndexOf('}');
        return start >= 0 && end > start ? clean.substring(start, end + 1) : clean;
    }
}

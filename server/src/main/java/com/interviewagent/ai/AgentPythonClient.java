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

@Component
public class AgentPythonClient {
    private final ObjectMapper json;
    private final String url;
    private final String key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AgentPythonClient(ObjectMapper json, @Value("${app.agent.url:}") String url, @Value("${app.agent.internal-key:}") String key) {
        this.json = json; this.url = url.replaceAll("/+$", ""); this.key = key;
    }

    public String reply(String userId, String conversationId, List<Map<String, Object>> messages, String context) {
        if (url.isBlank() || key.isBlank()) throw new ReviewFailedException("Agent 服务尚未配置，请联系管理员后重试。");
        try {
            String body = json.writeValueAsString(Map.of("userId", userId, "conversationId", conversationId, "messages", messages, "context", context));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/v1/agent/reply")).timeout(Duration.ofSeconds(90))
                .header("X-Agent-Key", key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ReviewFailedException(result.path("error").asText("Agent 服务请求失败，请稍后重试。"));
            String answer = result.path("content").asText("").trim();
            if (answer.isBlank()) throw new ReviewFailedException("Agent 服务未返回有效回复，请重试。");
            return answer;
        } catch (ReviewFailedException exception) { throw exception;
        } catch (Exception exception) { throw new ReviewFailedException("Agent 服务暂时不可用，请稍后重试。"); }
    }
}

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

/** Model gateway for reviews, recording imports and weakness analysis. Simulations and chat use Python. */
@Component
public class ReviewModelClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReviewModelClient.class);
    private final ObjectMapper json;
    private final String url;
    private final String apiKey;
    private final String model;
    @Value("${app.review-model.request-timeout-seconds:240}") private int reviewTimeoutSeconds = 240;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ReviewModelClient(ObjectMapper json, @Value("${app.review-model.url:}") String url, @Value("${app.review-model.api-key:}") String apiKey, @Value("${app.review-model.model:}") String model) {
        this.json = json; this.url = url; this.apiKey = apiKey; this.model = model;
    }

    public JsonNode review(String prompt) {
        return jsonReply(prompt, "AI 复盘", reviewTimeoutSeconds, 8192);
    }

    public String reply(String prompt) {
        JsonNode response = request(Map.of("model", model, "temperature", 0.2, "messages", List.of(Map.of("role", "user", "content", prompt))));
        if ("length".equals(response.path("choices").path(0).path("finish_reason").asText())) throw new ReviewFailedException("TRUNCATED", "AI 输出被截断，请重试。", null);
        String content = content(response);
        if (content.isBlank()) throw new ReviewFailedException("CONTENT_MISSING", "AI 响应缺少 content，请重试。", null);
        return content;
    }

    public JsonNode replyJson(String prompt) {
        return jsonReply(prompt, "AI");
    }

    public JsonNode importJson(String prompt, int timeoutSeconds) { return jsonReply(prompt, "问答分析", timeoutSeconds, 4096); }

    private JsonNode jsonReply(String prompt, String label) { return jsonReply(prompt, label, 60, 8192); }
    private JsonNode jsonReply(String prompt, String label, int timeoutSeconds, int tokens) {
        try {
        JsonNode response = request(Map.of("model", model, "temperature", 0.2, "max_tokens", tokens, "response_format", Map.of("type", "json_object"), "messages", List.of(Map.of("role", "user", "content", prompt))), timeoutSeconds);
        if ("length".equals(response.path("choices").path(0).path("finish_reason").asText())) throw new ReviewFailedException("TRUNCATED", label + "输出被截断，请缩小输入后重试。", null);
        String content = content(response);
        if (content.isBlank()) throw new ReviewFailedException("CONTENT_MISSING", label + "响应缺少 content，请重试。", null);
        try { JsonNode root = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(jsonText(content)); if (root == null || !root.isObject()) throw new IllegalArgumentException("not an object"); return root; }
        catch (Exception exception) { throw new ReviewFailedException("INVALID_JSON", label + "模型输出非法 JSON，请重试。", exception); }
        } catch (ReviewFailedException e) {
            log.warn("importId={} block={} stage=MODEL errorType={} causeType={}", org.slf4j.MDC.get("importId"), org.slf4j.MDC.get("importBlock"), e.code(), e.getCause()==null ? "" : e.getCause().getClass().getSimpleName());
            throw e;
        }
    }

    private JsonNode request(Map<String, Object> body) { return request(body, 60); }
    private JsonNode request(Map<String, Object> body, int timeoutSeconds) {
        if (url.isBlank() || apiKey.isBlank() || model.isBlank()) throw new ReviewFailedException("CONFIGURATION", "AI 服务尚未配置，请联系管理员后重试。", null);
        long started = System.nanoTime(); int status = 0; String requestId = ""; int outputLength = 0;
        String error = "OK";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode(); outputLength = response.body().length();
            requestId = response.headers().firstValue("x-request-id").orElse(response.headers().firstValue("request-id").orElse(""));
            if (status / 100 != 2) {
                String code = status == 401 || status == 403 ? "AUTHENTICATION" : status == 429 ? "HTTP_429" : status >= 500 ? "HTTP_5XX" : "HTTP_" + status;
                throw new ReviewFailedException(code, "AI 服务 HTTP " + status + (status == 401 || status == 403 ? "，请检查鉴权配置。" : "，请稍后重试。"), null);
            }
            try { JsonNode root = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body()); if (root == null || !root.isObject()) throw new IllegalArgumentException("not an object"); return root; }
            catch (Exception e) { throw new ReviewFailedException("PROVIDER_JSON", "AI 供应商外层响应不是有效 JSON。", e); }
        } catch (ReviewFailedException e) { error = e.code(); throw e;
        } catch (java.net.http.HttpTimeoutException e) { error = "HTTP_TIMEOUT"; throw new ReviewFailedException(error, "AI 请求超时，请重试。", e);
        } catch (java.io.IOException e) { error = "CONNECTION"; throw new ReviewFailedException(error, "AI 连接失败，请重试。", e);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); error = "INTERRUPTED"; throw new ReviewFailedException(error, "AI 请求已中断。", e);
        } catch (Exception e) { error = "CONFIGURATION"; throw new ReviewFailedException(error, "AI 请求配置无效。", e);
        } finally { log.info("importId={} block={} model={} inputChars={} outputChars={} httpStatus={} elapsedMs={} errorType={} requestId={}", org.slf4j.MDC.get("importId"), org.slf4j.MDC.get("importBlock"), model, body.toString().length(), outputLength, status, (System.nanoTime()-started)/1_000_000, error, safeRequestId(requestId)); }
    }
    private static String safeRequestId(String value) { String safe=value.replaceAll("[^a-zA-Z0-9._-]", ""); return safe.substring(0,Math.min(128,safe.length())); }

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

package com.interviewagent.aimock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Shared server-side transcription gateway; browser clients never receive its credentials. */
@Service
public class AudioTranscriptionService {
    private final String url, key, model, volcengineKey;
    private final ObjectMapper json;
    public AudioTranscriptionService(ObjectMapper json, @Value("${app.transcription.url:}") String url, @Value("${app.transcription.api-key:}") String key, @Value("${app.transcription.model:}") String model, @Value("${VOLCENGINE_SPEECH_API_KEY:}") String volcengineKey) { this.json = json; this.url = url; this.key = key; this.model = model; this.volcengineKey = volcengineKey; }
    public String transcribe(String userId, byte[] bytes, String type) { return volcengineKey.isBlank() ? openAi(bytes, type) : volcengine(userId, bytes, type); }
    private String volcengine(String userId, byte[] bytes, String type) {
        if (!type.equals("audio/ogg") && !type.equals("audio/wav") && !type.equals("audio/mpeg")) throw new IllegalStateException("当前转写服务仅支持 OGG、WAV 或 MP3；请转换后重新上传。");
        try {
            String body = json.writeValueAsString(Map.of("user", Map.of("uid", userId), "audio", Map.of("data", Base64.getEncoder().encodeToString(bytes)), "request", Map.of("model_name", "bigmodel", "enable_itn", true, "enable_punc", true)));
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(HttpRequest.newBuilder(URI.create("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash")).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").header("X-Api-Key", volcengineKey).header("X-Api-Resource-Id", "volc.bigasr.auc_turbo").header("X-Api-Request-Id", UUID.randomUUID().toString()).header("X-Api-Sequence", "-1").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (!"20000000".equals(response.headers().firstValue("X-Api-Status-Code").orElse(""))) throw new IllegalStateException("转写服务请求失败，请稍后重试。");
            String text = json.readTree(response.body()).path("result").path("text").asText("").trim();
            if (text.isBlank()) throw new IllegalStateException("转写服务未返回有效文本，请重新上传或手工整理。");
            return text;
        } catch (IllegalStateException exception) { throw exception; } catch (Exception exception) { throw new IllegalStateException("转写服务超时或失败，请稍后重试。", exception); }
    }
    private String openAi(byte[] bytes, String type) {
        if (url.isBlank() || key.isBlank() || model.isBlank()) throw new IllegalStateException("转写服务尚未配置，请联系管理员后重试。");
        try {
            String boundary = "----audio" + UUID.randomUUID();
            byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8); byte[] body = new byte[head.length + bytes.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length); System.arraycopy(bytes, 0, body, head.length, bytes.length); System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + key).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("转写服务请求失败，请稍后重试。");
            String text = json.readTree(response.body()).path("text").asText("").trim();
            if (text.isBlank()) throw new IllegalStateException("转写服务未返回有效文本，请重新上传或手工整理。");
            return text;
        } catch (IllegalStateException exception) { throw exception; } catch (Exception exception) { throw new IllegalStateException("转写服务超时或失败，请稍后重试。", exception); }
    }
}

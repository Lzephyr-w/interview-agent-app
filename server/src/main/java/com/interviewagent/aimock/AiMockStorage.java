package com.interviewagent.aimock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class AiMockStorage {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String url, bucket, key;
    AiMockStorage(@Value("${app.supabase.storage-url}") String url, @Value("${app.ai-mock-audio.bucket:ai-mock-audio}") String bucket, @Value("${SUPABASE_STORAGE_SERVICE_KEY:}") String key) { this.url = url.replaceAll("/+$", ""); this.bucket = bucket; this.key = key; }
    void upload(String path, String type, byte[] bytes) { send(HttpRequest.newBuilder(uri(path)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding()); }
    byte[] download(String path) { return send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body(); }
    void delete(String path) { send(HttpRequest.newBuilder(uri(path)).DELETE().build(), HttpResponse.BodyHandlers.discarding()); }
    private URI uri(String path) { return URI.create(url + "/object/" + bucket + "/" + path); }
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) {
        if (key.isBlank()) throw new IllegalStateException("服务器未配置音频存储访问凭据。");
        try { HttpResponse<T> response = client.send(HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(60)).method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).headers("Authorization", "Bearer " + key, "apikey", key, "Content-Type", request.headers().firstValue("Content-Type").orElse("application/octet-stream")).build(), body); if (response.statusCode() / 100 != 2) throw new IllegalStateException(storageError(response.statusCode())); return response; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("音频存储暂时不可用。", e); }
        catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("连接音频存储失败，请检查网络后重试。", e); }
    }
    static String storageError(int status) { return status==413?"音频存储拒绝了过大的录音，请缩短回答后重试。":status==429?"音频存储请求过于频繁，请稍后重试。":"音频存储请求失败（HTTP "+status+"），请稍后重试。"; }
}

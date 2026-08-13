package com.interviewagent.material;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ResumeFileStorage {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String storageUrl;
    private final String bucket;
    private final String serviceKey;

    ResumeFileStorage(
        @Value("${app.supabase.storage-url}") String storageUrl,
        @Value("${app.resume-files.bucket}") String bucket,
        @Value("${SUPABASE_STORAGE_SERVICE_KEY:}") String serviceKey
    ) {
        this.storageUrl = storageUrl.replaceAll("/+$", "");
        this.bucket = bucket;
        this.serviceKey = serviceKey;
    }

    public void upload(String objectPath, String contentType, byte[] content) {
        send(HttpRequest.newBuilder(objectUri(objectPath))
            .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.discarding());
    }

    public byte[] download(String objectPath) {
        return send(HttpRequest.newBuilder(objectUri(objectPath)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    public void delete(String objectPath) {
        send(HttpRequest.newBuilder(objectUri(objectPath)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    private URI objectUri(String objectPath) {
        return URI.create(storageUrl + "/object/" + bucket + "/" + objectPath);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        if (serviceKey.isBlank()) throw new IllegalStateException("服务器未配置 Supabase Storage 访问凭据。");
        try {
            HttpRequest.Builder authenticated = HttpRequest.newBuilder(request.uri())
                .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            request.headers().map().forEach((name, values) -> values.forEach(value -> authenticated.header(name, value)));
            HttpResponse<T> response = client.send(authenticated.header("Authorization", "Bearer " + serviceKey).header("apikey", serviceKey).build(), handler);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("文件存储暂时不可用。");
            return response;
        } catch (IOException exception) {
            throw new IllegalStateException("文件存储暂时不可用。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件存储暂时不可用。", exception);
        }
    }
}

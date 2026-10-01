package com.interviewagent.ai.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Server-side private audio storage shared by AI mock and real interview import. */
@Service
public class AiAudioStorage {
    private static final Logger log = LoggerFactory.getLogger(AiAudioStorage.class);
    private final HttpClient client;
    private final String url, bucket, key;
    private final ObjectMapper json;

    public AiAudioStorage(ObjectMapper json, @Value("${app.supabase.storage-url}") String url, @Value("${app.ai-mock-audio.bucket:ai-mock-audio}") String bucket, @Value("${SUPABASE_STORAGE_SERVICE_KEY:}") String key) {
        this.json=json; this.url = url.replaceAll("/+$", ""); this.bucket = bucket; this.key = key; this.client = httpClient();
    }

    public void upload(String path, String type, byte[] bytes) { send(HttpRequest.newBuilder(uri(path)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding()); }
    public byte[] download(String path) { return send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body(); }
    public void delete(String path) { send(HttpRequest.newBuilder(uri(path)).DELETE().build(), HttpResponse.BodyHandlers.discarding()); }
    public String signedUrl(String path) { return signedUrl(bucket, path); }

    private String signedUrl(String targetBucket, String path) {
        try {
            String body=json.writeValueAsString(java.util.Map.of("expiresIn",3600));
            String response=send(HttpRequest.newBuilder(URI.create(url+"/object/sign/"+targetBucket+"/"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()).body();
            String signed=json.readTree(response).path("signedURL").asText();
            if(signed.isBlank()) throw new IllegalStateException("无法生成录音临时访问链接。");
            return signed.startsWith("http")?signed:url+(signed.startsWith("/")?signed:"/"+signed);
        } catch(IllegalStateException exception) { throw exception; }
        catch(Exception exception) { throw new IllegalStateException("无法生成录音临时访问链接。",exception); }
    }

    private URI uri(String path) { return URI.create(url + "/object/" + bucket + "/" + path); }
    private static HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        String proxy = System.getenv("HTTPS_PROXY"); if (proxy == null || proxy.isBlank()) proxy = System.getenv("HTTP_PROXY");
        if (proxy != null && !proxy.isBlank()) { URI uri = URI.create(proxy); int port = uri.getPort() > 0 ? uri.getPort() : 80; builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port))); }
        return builder.build();
    }
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) {
        return send(request, body, Duration.ofSeconds(60));
    }
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body, Duration timeout) {
        if (key.isBlank()) throw new IllegalStateException("服务器未配置音频存储访问凭据。");
        long started=System.nanoTime();
        try {
            HttpResponse<T> response = client.send(HttpRequest.newBuilder(request.uri()).timeout(timeout).method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).headers("Authorization", "Bearer " + key, "apikey", key, "Content-Type", request.headers().firstValue("Content-Type").orElse("application/octet-stream")).build(), body);
            if (response.statusCode() / 100 != 2) {
                log.warn("audio_storage_response method={} host={} status={} elapsed_ms={}",request.method(),request.uri().getHost(),response.statusCode(),(System.nanoTime()-started)/1_000_000);
                throw new IllegalStateException(storageError(response.statusCode()));
            }
            return response;
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("音频存储暂时不可用。", exception);
        } catch (IllegalStateException exception) { throw exception;
        } catch (Exception exception) {
            Throwable root=exception;
            while(root.getCause()!=null&&root.getCause()!=root) root=root.getCause();
            boolean proxyConfigured=java.util.stream.Stream.of("HTTPS_PROXY","HTTP_PROXY","https_proxy","http_proxy").anyMatch(name->{String value=System.getenv(name);return value!=null&&!value.isBlank();});
            log.warn("audio_storage_transport_failure method={} host={} elapsed_ms={} proxy_configured={} cause_type={} root_cause_type={}",request.method(),request.uri().getHost(),(System.nanoTime()-started)/1_000_000,proxyConfigured,exception.getClass().getName(),root.getClass().getName());
            throw new IllegalStateException("连接音频存储失败，请检查网络后重试。", exception);
        }
    }
    public static String storageError(int status) { return status == 413 ? "音频存储拒绝了过大的录音，请缩短回答后重试。" : status == 429 ? "音频存储请求过于频繁，请稍后重试。" : "音频存储请求失败（HTTP " + status + "），请稍后重试。"; }
}

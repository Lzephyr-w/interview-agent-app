package com.interviewagent.ai.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.math.BigInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Shared server-side transcription gateway; browser clients never receive its credentials. */
@Service
public class AudioTranscriptionService {
    private static final String TENCENT_HOST="asr.tencentcloudapi.com", TENCENT_VERSION="2019-06-14";
    private static final DateTimeFormatter TENCENT_DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private final String tencentSecretId, tencentSecretKey, tencentRegion, engineModelType;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AudioTranscriptionService.class);
    @Value("${app.interview-import.engine:}") private String importEngine = "";
    @Value("${app.interview-import.hotwords:}") private String importHotwords = "";
    private final ObjectMapper json;
    public record Turn(int segmentIndex, Integer speakerId, Long startMs, Long endMs, String text) {
        public String localSpeaker() { return segmentIndex + ":" + (speakerId == null ? "unknown" : speakerId); }
    }
    public record Transcript(String text, java.util.List<Turn> turns) {}
    public Transcript transcribeImport(String taskId, byte[] bytes, int segmentIndex, long offsetMs) {
        String engine = importEngine.isBlank() ? engineModelType : importEngine;
        if (!java.util.Set.of("16k_zh", "16k_en", "16k_zh_en", "16k_zh_en_2.0", "16k_zh_en_meeting").contains(engine)) throw new IllegalStateException("导入引擎不支持带标点的说话人分离，请检查 INTERVIEW_IMPORT_ASR_ENGINE。");
        validateHotwords(importHotwords, engine);
        long started = System.nanoTime();
        try (var task=org.slf4j.MDC.putCloseable("importId",taskId); var segment=org.slf4j.MDC.putCloseable("importSegment",Integer.toString(segmentIndex))) {
            try {
                Transcript result=decode(tencentResult(bytes, null, engine, true), segmentIndex, offsetMs);
                log.info("importId={} stage=ASR segment={} engine={} inputBytes={} outputChars={} elapsedMs={}",taskId,segmentIndex,engine,bytes.length,result.text().length(),(System.nanoTime()-started)/1_000_000);
                return result;
            } catch(RuntimeException e) {
                log.warn("importId={} stage=ASR segment={} engine={} inputBytes={} elapsedMs={} errorType={} causeType={}",taskId,segmentIndex,engine,bytes.length,(System.nanoTime()-started)/1_000_000,e.getClass().getSimpleName(),e.getCause()==null?"":e.getCause().getClass().getSimpleName());
                throw e;
            }
        }
    }
    public static Transcript decode(JsonNode status, int segment, long offset) {
        java.util.List<Turn> turns = new java.util.ArrayList<>(); StringBuilder text = new StringBuilder();
        for (JsonNode detail : status.path("ResultDetail")) {
            String sentence = detail.path("FinalSentence").asText("").trim(); if (sentence.isBlank()) continue;
            Long start = detail.path("StartMs").isIntegralNumber() ? offset + detail.path("StartMs").asLong() : null;
            Long end = detail.path("EndMs").isIntegralNumber() ? offset + detail.path("EndMs").asLong() : null;
            if (start != null && (start < offset || end == null || end < start)) throw new IllegalStateException("ASR 时间范围无效。");
            turns.add(new Turn(segment, detail.path("SpeakerId").isIntegralNumber() ? detail.path("SpeakerId").asInt() : null, start, end, sentence));
            text.append(sentence).append('\n');
        }
        if (turns.isEmpty()) {
            String fallback = status.path("Result").asText("").replaceAll("(?m)^\\[[^\\]]+\\]\\s*", "").trim();
            if (!fallback.isBlank()) { text.append(fallback); turns.add(new Turn(segment, null, null, null, fallback)); }
        }
        return new Transcript(text.toString().trim(), java.util.List.copyOf(turns));
    }
    static void validateHotwords(String value, String engine) {
        if (value.isBlank()) return;
        String[] words = value.split(",", -1); if (words.length > 128) throw new IllegalStateException("热词最多 128 个。");
        for (String entry : words) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[0].codePointCount(0, parts[0].length()) > 30 || parts[0].codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count() > 10 || !parts[1].matches("(?:[1-9]|10|11|100)")) throw new IllegalStateException("热词须为 热词|权重，最多 30 字符/10 汉字，权重 1–11 或 100。");
            if (parts[1].equals("100") && !engine.equals("16k_zh")) throw new IllegalStateException("权重 100 仅支持 16k_zh 导入引擎。");
        }
    }
    public AudioTranscriptionService(ObjectMapper json, @Value("${app.tencent-asr.secret-id:}") String tencentSecretId, @Value("${app.tencent-asr.secret-key:}") String tencentSecretKey, @Value("${app.tencent-asr.region:ap-shanghai}") String tencentRegion, @Value("${app.tencent-asr.engine-model-type:16k_zh}") String engineModelType) { this.json=json; this.tencentSecretId=tencentSecretId; this.tencentSecretKey=tencentSecretKey; this.tencentRegion=tencentRegion; this.engineModelType=engineModelType; }
    public String transcribe(String userId, byte[] bytes, String type) { return transcribe(userId,bytes,type,null); }
    public String transcribe(String userId, byte[] bytes, String type, String audioUrl) {
        if(tencentSecretId.isBlank()||tencentSecretKey.isBlank()) throw new IllegalStateException("腾讯云语音识别尚未配置，请在服务端设置 TENCENT_CLOUD_SECRET_ID 和 TENCENT_CLOUD_SECRET_KEY。");
        return tencent(bytes,audioUrl);
    }
    private String tencent(byte[] bytes,String audioUrl) {
        JsonNode result = tencentResult(bytes, audioUrl, engineModelType, false);
        StringBuilder text = new StringBuilder();
        for (JsonNode detail : result.path("ResultDetail")) text.append(detail.path("FinalSentence").asText(""));
        return text.isEmpty() ? decode(result, 0, 0).text() : text.toString().trim();
    }
    private JsonNode tencentResult(byte[] bytes, String audioUrl, String engine, boolean detailed) {
        if(tencentSecretId.isBlank()||tencentSecretKey.isBlank()) throw new IllegalStateException("腾讯云语音识别尚未配置。");
        if((audioUrl==null||audioUrl.isBlank())&&bytes.length>5_000_000) throw new IllegalStateException("腾讯云直传音频限制为 5,000,000 字节。");
        try {
            Map<String,Object> request=new java.util.HashMap<>(Map.of("EngineModelType",engine,"ChannelNum",1,"ResTextFormat",detailed ? 2 : 1));
            if (detailed) { request.put("SpeakerDiarization", 1); if (!importHotwords.isBlank()) request.put("HotwordList", importHotwords); }
            if(audioUrl==null||audioUrl.isBlank()) { request.put("SourceType",1); request.put("Data",Base64.getEncoder().encodeToString(bytes)); request.put("DataLen", bytes.length); }
            else { request.put("SourceType",0); request.put("Url",audioUrl); }
            String taskId=callTencent("CreateRecTask",request).path("Data").path("TaskId").asText();
            if(taskId.isBlank()) throw new IllegalStateException("腾讯云未返回识别任务编号。");
            for(int attempt=0;attempt<2160;attempt++) {
                JsonNode status=callTencent("DescribeTaskStatus",Map.of("TaskId",new BigInteger(taskId))).path("Data");
                int code=status.path("Status").asInt(-1);
                if(code==2) return status;
                if(code==3) throw new IllegalStateException("腾讯云录音识别失败，供应商错误码："+status.path("ErrorCode").asText("UNKNOWN"));
                Thread.sleep(5000);
            }
            throw new IllegalStateException("腾讯云录音识别仍在处理中，请稍后重试。");
        } catch(IllegalStateException exception) { throw exception; }
        catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("腾讯云录音识别已中断。",exception); }
        catch(Exception exception) { throw new IllegalStateException("腾讯云录音识别请求失败，请稍后重试。",exception); }
    }
    JsonNode callTencent(String action,Map<String,?> params) throws Exception {
        String body=json.writeValueAsString(params), contentType="application/json; charset=utf-8";
        long timestamp=Instant.now().getEpochSecond(); String date=TENCENT_DATE.format(Instant.ofEpochSecond(timestamp));
        String canonical="POST\n/\n\ncontent-type:"+contentType+"\nhost:"+TENCENT_HOST+"\n\ncontent-type;host\n"+sha256(body);
        String scope=date+"/asr/tc3_request";
        String toSign="TC3-HMAC-SHA256\n"+timestamp+"\n"+scope+"\n"+sha256(canonical);
        byte[] dateKey=hmac(("TC3"+tencentSecretKey).getBytes(StandardCharsets.UTF_8),date);
        byte[] serviceKey=hmac(dateKey,"asr"), signingKey=hmac(serviceKey,"tc3_request");
        String signature=java.util.HexFormat.of().formatHex(hmac(signingKey,toSign));
        String authorization="TC3-HMAC-SHA256 Credential="+tencentSecretId+"/"+scope+", SignedHeaders=content-type;host, Signature="+signature;
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://"+TENCENT_HOST+"/")).timeout(Duration.ofSeconds(30))
            .header("Content-Type",contentType).header("Authorization",authorization)
            .header("X-TC-Action",action).header("X-TC-Version",TENCENT_VERSION).header("X-TC-Timestamp",Long.toString(timestamp)).header("X-TC-Region",tencentRegion)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(request,HttpResponse.BodyHandlers.ofString());
        JsonNode root=json.readTree(response.body()).path("Response");
        log.info("importId={} segment={} stage=ASR action={} httpStatus={} requestId={} errorType={}", org.slf4j.MDC.get("importId"), org.slf4j.MDC.get("importSegment"), action, response.statusCode(), root.path("RequestId").asText("").replaceAll("[^a-zA-Z0-9._-]",""), root.path("Error").path("Code").asText("OK"));
        if(response.statusCode()/100!=2||root.has("Error")) throw new IllegalStateException("腾讯云 ASR 请求失败："+root.path("Error").path("Code").asText("HTTP "+response.statusCode()));
        return root;
    }
    private static String sha256(String value) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static byte[] hmac(byte[] key,String value) throws Exception { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
}

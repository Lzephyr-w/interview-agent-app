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
import java.util.UUID;
import java.net.http.HttpTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentPythonClient {
    private static final Logger log = LoggerFactory.getLogger(AgentPythonClient.class);
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

    public JsonNode simulate(String operation, Map<String, Object> input) {
        return simulate(operation, input, System.currentTimeMillis()+70_000);
    }

    JsonNode simulate(String operation, Map<String, Object> input, long deadline) {
        String requestId=UUID.randomUUID().toString(), code="OK";
        long started=System.currentTimeMillis();
        try {
            SimulationContract.input(operation,json.valueToTree(input));
            long remaining=Math.min(deadline,started+70_000)-System.currentTimeMillis();
            if (remaining<=0) throw new SimulationException("MODEL_TIMEOUT");
            if (url.isBlank() || key.isBlank()) throw new SimulationException("MODEL_UNAVAILABLE");
            String body=json.writeValueAsString(Map.of("version","simulation.v1","requestId",requestId,"operation",operation,"deadlineAtEpochMs",Math.min(deadline,started+70_000),"input",input));
            var request=HttpRequest.newBuilder(URI.create(url+"/v1/agent/simulations"))
                .timeout(Duration.ofMillis(remaining)).header("X-Agent-Key",key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.body().length()>32000) throw SimulationContract.invalid();
            JsonNode envelope;
            try { envelope=json.readTree(response.body()); } catch (Exception error) { throw SimulationContract.invalid(); }
            if (envelope==null || !envelope.path("version").isTextual() || !envelope.path("version").asText().equals("simulation.v1")
                || !envelope.path("requestId").isTextual() || !envelope.path("requestId").asText().equals(requestId)) throw SimulationContract.invalid();
            if (envelope.has("error")) {
                SimulationContract.fields(envelope,"version","requestId","error");
                JsonNode error=envelope.path("error");
                SimulationContract.fields(error,"code","message","retryable");
                String errorCode=SimulationContract.text(error,"code",40,false);
                SimulationContract.text(error,"message",240,false);
                if (!SimulationContract.CODES.contains(errorCode) || !error.path("retryable").isBoolean()
                    || error.path("retryable").asBoolean()!=new SimulationException(errorCode).retryable()) throw SimulationContract.invalid();
                throw new SimulationException(errorCode);
            }
            SimulationContract.fields(envelope,"version","requestId","result");
            if (response.statusCode()!=200) throw SimulationContract.invalid();
            SimulationContract.result(operation,envelope.path("result"));
            return envelope.path("result");
        } catch (SimulationException error) { code=error.code(); throw error;
        } catch (HttpTimeoutException error) { code="MODEL_TIMEOUT"; throw new SimulationException(code);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); code="MODEL_TIMEOUT"; throw new SimulationException(code);
        } catch (Exception error) { code="MODEL_UNAVAILABLE"; throw new SimulationException(code);
        } finally { log.info("simulation requestId={} taskId={} sessionId={} operation={} code={} elapsed_ms={}",requestId,org.slf4j.MDC.get("taskId"),org.slf4j.MDC.get("sessionId"),SimulationContract.OPERATIONS.contains(String.valueOf(operation))?operation:"INVALID",code,System.currentTimeMillis()-started); }
    }
}

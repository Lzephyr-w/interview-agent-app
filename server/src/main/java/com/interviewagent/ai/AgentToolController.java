package com.interviewagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/agent/tools")
public class AgentToolController {
    private final AgentToolService tools;
    private final byte[] key;

    public AgentToolController(AgentToolService tools, @Value("${app.agent.internal-key:}") String key) {
        this.tools = tools; this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    Object execute(@RequestHeader(value = "X-Agent-Key", defaultValue = "") String requestKey, @RequestBody ToolRequest request) {
        if (key.length == 0 || !MessageDigest.isEqual(key, requestKey.getBytes(StandardCharsets.UTF_8))) throw new AgentUnauthorizedException();
        if (request.userId() == null || request.userId().isBlank()) throw new IllegalArgumentException("用户上下文缺失。");
        if (request.name() == null || request.name().isBlank() || request.arguments() == null) throw new IllegalArgumentException("工具请求格式无效。");
        return tools.execute(request.userId(), request.name(), request.arguments());
    }

    record ToolRequest(String userId, String name, JsonNode arguments) {}
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    static class AgentUnauthorizedException extends RuntimeException {}
}

package com.interviewagent.ai;

import com.interviewagent.interview.ReviewFailedException;

public class SimulationException extends ReviewFailedException {
    private final String code;
    private final boolean retryable;
    public SimulationException(String code) { this(code, code.equals("MODEL_TIMEOUT") || code.equals("MODEL_UNAVAILABLE")); }
    public SimulationException(String code, boolean retryable) { super(message(code)); this.code = code; this.retryable = retryable; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public static boolean validRetryable(String code, boolean retryable) {
        if (code.equals("MODEL_TIMEOUT") || code.equals("MODEL_UNAVAILABLE")) return retryable;
        return code.equals("INVALID_MODEL_OUTPUT") || !retryable;
    }
    public static String message(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> "模拟请求格式无效，请重新开始。";
            case "MODEL_TIMEOUT" -> "AI 模拟处理超时，请稍后重试。";
            case "MODEL_UNAVAILABLE" -> "AI 模拟服务暂时不可用，请稍后重试。";
            case "UNAUTHORIZED" -> "模拟服务认证失败，请联系管理员。";
            case "INVALID_MODEL_OUTPUT" -> "AI 模拟返回格式或内容无效，请重试。";
            default -> "AI 模拟处理失败，请稍后重试。";
        };
    }
}

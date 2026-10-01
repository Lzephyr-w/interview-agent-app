package com.interviewagent.interview;

public class ReviewFailedException extends RuntimeException {
    private final String code;
    public ReviewFailedException(String message) { this("UNKNOWN", message, null); }
    public ReviewFailedException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
    public boolean retryable() { return java.util.Set.of("HTTP_TIMEOUT", "CONNECTION", "HTTP_429", "HTTP_5XX", "INVALID_JSON").contains(code); }
}

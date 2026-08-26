package com.interviewagent.ai;

import java.time.OffsetDateTime;

public final class AiTaskApi {
    private AiTaskApi() {}

    public record Task(
        String id, String taskType, String resourceId, String status,
        int attempts, int maxAttempts, String error,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}
}

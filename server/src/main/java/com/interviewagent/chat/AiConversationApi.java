package com.interviewagent.chat;

import java.time.OffsetDateTime;
import java.util.List;

public final class AiConversationApi {
    private AiConversationApi() {}

    public record ConversationRequest(String interviewPackageId, String interviewId, String reviewReportId, String weaknessTag, String title) {}
    public record MessageRequest(String content, String clientRequestId) {}
    public record ContextSource(String type, String label, String state) {}
    public record ConversationSummary(String id, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record Conversation(String id, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt, List<ContextSource> contextSources) {}
    public record Message(String id, String role, String content, String status, String errorMessage, String clientRequestId, String replyToMessageId, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record ConversationDetail(Conversation conversation, List<Message> messages) {}
}

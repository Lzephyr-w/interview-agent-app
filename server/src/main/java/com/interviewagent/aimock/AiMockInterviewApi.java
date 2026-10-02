package com.interviewagent.aimock;

import com.interviewagent.ai.AiTaskApi.Task;
import java.time.OffsetDateTime;
import java.util.List;

public final class AiMockInterviewApi {
    private AiMockInterviewApi() {}
    public record StartRequest(String interviewPackageId, String sourceMode, List<String> categoryIds) {
        public StartRequest(String interviewPackageId) { this(interviewPackageId, null, null); }
    }
    public record AnswerStart(String questionId, OffsetDateTime answerExpiresAt) {}
    public record ConfirmRequest(String questionId, String answerText) {}
    public record AudioUploadRequest(long totalBytes, String sha256) {}
    public record AudioUpload(String id, int chunkSizeBytes, long totalBytes, int totalParts, List<Integer> receivedParts, String status, OffsetDateTime expiresAt) {}
    public record Audio(String id, String status, String transcript, String transcriptError, String feedback, Long durationMs) {}
    public record Question(String id, String questionText, String questionType, String competency, String confirmedAnswerText, String state, int sortOrder, OffsetDateTime answerExpiresAt, Audio audio, String sourceTitle, String sourceLocation) {}
    public record NextPreview(String questionText, int sortOrder, String sourceTitle, String sourceLocation) {}
    public record Session(String id, String company, String role, String interviewRound, String status, String sourceMode, OffsetDateTime startedAt, String finalInterviewId, int totalQuestions, Question currentQuestion, Task task, String generationVersion, boolean prepared, int completedQuestions) {}
}

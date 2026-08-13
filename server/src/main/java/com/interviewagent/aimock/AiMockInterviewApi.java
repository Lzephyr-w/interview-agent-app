package com.interviewagent.aimock;

import java.time.OffsetDateTime;
import java.util.List;

public final class AiMockInterviewApi {
    private AiMockInterviewApi() {}
    public record StartRequest(String interviewPackageId) {}
    public record ConfirmRequest(String questionId, String answerText) {}
    public record Audio(String id, String status, String transcript, String transcriptError, String feedback, Long durationMs) {}
    public record Question(String id, String questionText, String questionType, String competency, String confirmedAnswerText, String state, int sortOrder, OffsetDateTime answerExpiresAt, Audio audio) {}
    public record Session(String id, String company, String role, String interviewRound, String status, OffsetDateTime startedAt, String finalInterviewId, int totalQuestions, Question currentQuestion) {}
}

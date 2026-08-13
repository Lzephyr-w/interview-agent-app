package com.interviewagent.weakness;

import java.time.OffsetDateTime;
import java.util.List;

public final class WeaknessApi {
    private WeaknessApi() {}

    public record WeaknessItem(String tag, int count, TrainingSuggestion suggestion, List<WeaknessSource> sources) {}
    public record TrainingSuggestion(String title, String action, String reason, String missingEvidence, String recommendedStructure) {}
    public record WeaknessSource(String interviewId, String reviewReportId, String company, String role, String interviewRound, String interviewType, OffsetDateTime reviewedAt, List<WeaknessEvidence> evidence) {}
    public record WeaknessEvidence(String questionId, String questionText, String improvementAction, String missingEvidence, String recommendedAnswerStructure) {}

    public record TrainingTaskRequest(String title, String weaknessTag, String action, String status, String sourceInterviewId, String sourceReviewReportId) {}
    public record TrainingSource(String interviewId, String reviewReportId, String label, String interviewType) {}
    public record TrainingTask(String id, String title, String weaknessTag, String action, String status, OffsetDateTime createdAt, OffsetDateTime completedAt, TrainingSource source) {}
}

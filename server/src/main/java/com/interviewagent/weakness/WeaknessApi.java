package com.interviewagent.weakness;

import java.time.OffsetDateTime;
import java.util.List;

public final class WeaknessApi {
    private WeaknessApi() {}

    public record WeaknessAnalysis(String summary, OffsetDateTime analyzedAt, boolean stale, List<WeaknessItem> items) {}
    public record WeaknessItem(String tag, String title, String diagnosis, String action, List<WeaknessEvidence> evidence) {}
    public record WeaknessEvidence(String questionId, String reviewReportId, String interviewId, String questionText, String company, String role, String interviewRound, String interviewType, String reason) {}

    public record TrainingTaskRequest(String title, String weaknessTag, String action, String status, String sourceQuestionId, String sourceInterviewId, String sourceReviewReportId) {}
    public record TrainingSource(String questionId, String questionText, String interviewId, String reviewReportId, String label, String interviewType) {}
    public record TrainingTask(String id, String title, String weaknessTag, String action, String status, OffsetDateTime createdAt, OffsetDateTime completedAt, TrainingSource source) {}
}

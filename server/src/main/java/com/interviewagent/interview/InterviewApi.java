package com.interviewagent.interview;

import java.time.OffsetDateTime;
import java.util.List;

public final class InterviewApi {
    private InterviewApi() {}

    public record InterviewRequest(String company, String role, String interviewRound, OffsetDateTime interviewTime, String interviewPackageId, String status, String result, String notes) {}
    public record QuestionRequest(String questionText, String answerText, String selfAssessment) {}
    public record TranscriptRequest(String transcript) {}
    public record InterviewSummary(String id, String company, String role, String interviewRound, OffsetDateTime interviewTime, String status, String result, String interviewPackageId, String interviewType, String simulationType) {}
    public record InterviewQuestion(String id, String questionText, String answerText, String selfAssessment, int sortOrder) {}
    public record QuestionReview(String questionId, String evaluation, String answerEvidence, String missingEvidence, String improvementAction, String recommendedAnswerStructure, List<String> possibleFollowups) {}
    public record ReviewReport(String id, String readiness, String summary, List<String> weaknessTags, OffsetDateTime createdAt, List<QuestionReview> questionReviews) {}
    public record InterviewDetail(InterviewSummary interview, String notes, String transcript, List<InterviewQuestion> questions, List<ReviewReport> reviews) {}
}

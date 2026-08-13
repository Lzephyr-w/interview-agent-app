package com.interviewagent.mock;

import java.time.OffsetDateTime;
import java.util.List;

public final class MockInterviewApi {
    private MockInterviewApi() {}

    public record StartRequest(String interviewPackageId, String company, String role, String interviewRound) {}
    public record AnswerRequest(String questionId, String answerText, String selfAssessment) {}
    public record MockQuestion(
        String id, String questionText, String answerText, String aiFeedback, String selfAssessment,
        String questionKind, String parentQuestionId, String state, int sortOrder
    ) {}
    public record MockInterview(
        String id, String company, String role, String interviewRound, String status,
        String mode, boolean aiAvailable, String aiMessage, int totalQuestions,
        int completedQuestions, int currentQuestionIndex, String formalInterviewId,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, MockQuestion currentQuestion,
        List<MockQuestion> questions
    ) {}
}

package com.interviewagent.material;

public record ProjectEvidenceCard(
    String id, String projectName, String backgroundAndRole, String goalAndMetrics,
    String constraintsAndTradeoffs, String personalContribution, String resultAndRetrospective,
    String applicableQuestionTypes
) {}

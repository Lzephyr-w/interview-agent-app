package com.interviewagent.material;

import java.util.List;

public record InterviewPackage(
    String id, String company, String role, String interviewRound, String resumeFileId,
    String jobDescriptionId, List<String> evidenceCardIds
) {}

package com.interviewagent.material;

import java.util.List;

public final class MaterialRequests {
    private MaterialRequests() {}

    public record ResumeRequest(String title, String content) {}
    public record JobDescriptionRequest(String company, String role, String content) {}
    public record EvidenceCardRequest(
        String projectName, String technologyStack,
        String projectDescriptionAndResponsibilities, String projectHighlights
    ) {}
    public record InterviewPackageRequest(
        String company, String role, String interviewRound, String resumeFileId, String jobDescriptionId,
        List<String> evidenceCardIds
    ) {}
}

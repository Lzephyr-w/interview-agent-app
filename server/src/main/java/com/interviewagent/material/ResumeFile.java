package com.interviewagent.material;

import java.time.Instant;

public record ResumeFile(String id, String originalFilename, String contentType, long sizeBytes, String parseStatus, boolean parsedTruncated, Instant createdAt) {}

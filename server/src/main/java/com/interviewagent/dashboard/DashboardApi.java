package com.interviewagent.dashboard;

import java.time.OffsetDateTime;
import java.util.List;

public final class DashboardApi {
    private DashboardApi() {}

    public record Dashboard(Overview overview, List<Activity> recentActivities, List<WeaknessFocus> weaknesses, List<SprintItem> sprintItems) {}
    public record Overview(int interviewPackageCount, int resumeFileCount, int pendingReviewCount, int pendingTrainingTaskCount) {}
    public record Activity(String id, String type, String title, String detail, String targetPath, OffsetDateTime occurredAt) {}
    public record WeaknessFocus(String tag, String title, String targetPath) {}
    public record SprintItem(String id, String kind, String title, String description, String source, String targetPath, int priority, String status, boolean editable, OffsetDateTime updatedAt) {}
    public record SprintItemRequest(String title, String description, String targetPath, Integer priority, String status) {}
}

package com.interviewagent.dashboard;

import static com.interviewagent.dashboard.DashboardApi.*;

import com.interviewagent.weakness.WeaknessService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class DashboardService {
    private static final Set<String> TARGET_PATHS = Set.of("/library", "/interviews", "/interviews/new", "/mock-interviews", "/weaknesses", "/ai-conversations");
    private final JdbcClient jdbc;
    private final WeaknessService weaknessService;

    DashboardService(JdbcClient jdbc, WeaknessService weaknessService) { this.jdbc = jdbc; this.weaknessService = weaknessService; }

    Dashboard dashboard(String userId) {
        return new Dashboard(overview(userId), activities(userId), weaknesses(userId), sprintItems(userId));
    }

    SprintItem create(String userId, SprintItemRequest request) {
        String id = UUID.randomUUID().toString();
        ValidItem item = validate(request);
        jdbc.sql("INSERT INTO sprint_checklist_items (id, user_id, title, description, target_path, priority, status) VALUES (:id, :userId, :title, :description, :targetPath, :priority, :status)")
            .param("id", id).param("userId", userId).param("title", item.title()).param("description", item.description()).param("targetPath", item.targetPath()).param("priority", item.priority()).param("status", item.status()).update();
        return manualItem(userId, id);
    }

    SprintItem update(String userId, String id, SprintItemRequest request) {
        ValidItem item = validate(request);
        if (jdbc.sql("UPDATE sprint_checklist_items SET title = :title, description = :description, target_path = :targetPath, priority = :priority, status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).param("title", item.title()).param("description", item.description()).param("targetPath", item.targetPath()).param("priority", item.priority()).param("status", item.status()).update() == 0) throw notFound();
        return manualItem(userId, id);
    }

    void delete(String userId, String id) {
        if (jdbc.sql("DELETE FROM sprint_checklist_items WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).update() == 0) throw notFound();
    }

    private Overview overview(String userId) {
        return jdbc.sql("SELECT (SELECT COUNT(*) FROM interview_packages WHERE user_id = :userId) package_count, (SELECT COUNT(*) FROM resume_files WHERE user_id = :userId) resume_count, (SELECT COUNT(*) FROM interviews WHERE user_id = :userId AND interview_type = 'REAL' AND status = 'PENDING_REVIEW') review_count, (SELECT COUNT(*) FROM training_tasks WHERE user_id = :userId AND status <> 'COMPLETED') task_count")
            .param("userId", userId).query((rs, row) -> new Overview(rs.getInt("package_count"), rs.getInt("resume_count"), rs.getInt("review_count"), rs.getInt("task_count"))).single();
    }

    private List<Activity> activities(String userId) {
        return jdbc.sql("SELECT * FROM (SELECT 'REVIEW' type, r.id activity_id, i.company || ' · ' || i.role title, 'AI 复盘' detail, '/interviews/' || i.id || '/review' target_path, r.created_at occurred_at FROM review_reports r JOIN interviews i ON i.id = r.interview_id WHERE i.user_id = :userId UNION ALL SELECT CASE WHEN i.interview_type = 'MOCK' THEN 'MOCK' ELSE 'INTERVIEW' END, i.id, i.company || ' · ' || i.role, CASE WHEN i.interview_type = 'MOCK' THEN 'AI 模拟记录' ELSE '真实面试记录' END, '/interviews/' || i.id, i.updated_at FROM interviews i WHERE i.user_id = :userId) activity ORDER BY occurred_at DESC LIMIT 6")
            .param("userId", userId).query((rs, row) -> new Activity(rs.getString("activity_id"), rs.getString("type"), rs.getString("title"), rs.getString("detail"), rs.getString("target_path"), rs.getObject("occurred_at", OffsetDateTime.class))).list();
    }

    private List<WeaknessFocus> weaknesses(String userId) {
        return weaknessService.weaknesses(userId).stream()
            .map(item -> new WeaknessFocus(item.tag(), item.title(), "/weaknesses#" + java.net.URLEncoder.encode(item.tag(), java.nio.charset.StandardCharsets.UTF_8))).toList();
    }

    private List<SprintItem> sprintItems(String userId) {
        List<SprintItem> items = new ArrayList<>();
        jdbc.sql("SELECT id, title, description, target_path, priority, status, updated_at FROM sprint_checklist_items WHERE user_id = :userId ORDER BY status, priority DESC, updated_at DESC")
            .param("userId", userId).query((rs, row) -> item(rs)).list().forEach(items::add);
        jdbc.sql("SELECT id, title, action, status, source_interview_id FROM training_tasks WHERE user_id = :userId AND status <> 'COMPLETED' ORDER BY created_at DESC LIMIT 3")
            .param("userId", userId).query((rs, row) -> new SprintItem("training-" + rs.getString("id"), "TRAINING_TASK", rs.getString("title"), rs.getString("action"), "训练任务", rs.getString("source_interview_id") == null ? "/weaknesses" : "/interviews/" + rs.getString("source_interview_id") + "/review", 80, "TODO", false, null)).list().forEach(items::add);
        jdbc.sql("SELECT id, company, role FROM interviews WHERE user_id = :userId AND interview_type = 'REAL' AND status = 'PENDING_REVIEW' ORDER BY updated_at DESC LIMIT 3")
            .param("userId", userId).query((rs, row) -> new SprintItem("interview-" + rs.getString("id"), "PENDING_REVIEW", "复盘：" + rs.getString("company") + " · " + rs.getString("role"), "已有真实面试记录，尚未完成 AI 复盘。", "待复盘真实面试", "/interviews/" + rs.getString("id") + "/review", 70, "TODO", false, null)).list().forEach(items::add);
        jdbc.sql("SELECT id, company, role FROM mock_interviews WHERE user_id = :userId AND status = 'RUNNING' ORDER BY updated_at DESC LIMIT 1")
            .param("userId", userId).query((rs, row) -> new SprintItem("mock-" + rs.getString("id"), "MOCK", "继续 AI 文本模拟：" + rs.getString("company") + " · " + rs.getString("role"), "继续当前未完成的模拟练习。", "AI 文本模拟", "/mock-interviews", 60, "TODO", false, null)).list().forEach(items::add);
        weaknesses(userId).stream().map(item -> new SprintItem("weakness-" + item.tag(), "WEAKNESS", "聚焦薄弱点：" + item.title(), "查看 AI 汇总分析中的具体题目证据。", "AI 薄弱点分析", item.targetPath(), 50, "TODO", false, null)).forEach(items::add);
        return items.stream().sorted(Comparator.comparing((SprintItem item) -> !"TODO".equals(item.status())).thenComparing(SprintItem::priority, Comparator.reverseOrder())).limit(10).toList();
    }

    private SprintItem manualItem(String userId, String id) {
        return jdbc.sql("SELECT id, title, description, target_path, priority, status, updated_at FROM sprint_checklist_items WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).query((rs, row) -> item(rs)).optional().orElseThrow(DashboardService::notFound);
    }
    private SprintItem item(ResultSet rs) throws SQLException { return new SprintItem(rs.getString("id"), "MANUAL", rs.getString("title"), rs.getString("description"), "手动冲刺项", rs.getString("target_path"), rs.getInt("priority"), rs.getString("status"), true, rs.getObject("updated_at", OffsetDateTime.class)); }
    private ValidItem validate(SprintItemRequest request) {
        String title = required(request.title(), "冲刺项标题");
        String description = optional(request.description());
        String targetPath = optional(request.targetPath());
        if (targetPath != null && !TARGET_PATHS.contains(targetPath)) throw new IllegalArgumentException("跳转路径无效。");
        int priority = request.priority() == null ? 0 : request.priority();
        if (priority < 0 || priority > 100) throw new IllegalArgumentException("优先级必须在 0 到 100 之间。");
        String status = required(request.status(), "状态");
        if (!"TODO".equals(status) && !"DONE".equals(status)) throw new IllegalArgumentException("状态值无效。");
        return new ValidItem(title, description == null ? "" : description, targetPath == null ? "" : targetPath, priority, status);
    }
    private static String required(String value, String label) { String result = optional(value); if (result == null) throw new IllegalArgumentException(label + "不能为空。"); return result; }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static NoSuchElementException notFound() { return new NoSuchElementException("资源不存在或无权访问。"); }
    private record ValidItem(String title, String description, String targetPath, int priority, String status) {}
}

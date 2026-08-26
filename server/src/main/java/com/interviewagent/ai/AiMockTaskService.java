package com.interviewagent.ai;

import static com.interviewagent.ai.AiTaskApi.Task;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiMockTaskService {
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcClient jdbc;

    public AiMockTaskService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void enqueue(String userId, String type, String resourceId, String relatedId) {
        String related = relatedId == null ? "" : relatedId;
        if (jdbc.sql("SELECT id FROM ai_mock_tasks WHERE task_type=:type AND resource_id=:resource AND related_id=:related")
            .param("type", type).param("resource", resourceId).param("related", related).query(String.class).optional().isPresent()) return;
        jdbc.sql("INSERT INTO ai_mock_tasks(id,user_id,task_type,resource_id,related_id,status,max_attempts) VALUES(:id,:user,:type,:resource,:related,'PENDING',:max)")
            .param("id", UUID.randomUUID().toString()).param("user", userId).param("type", type).param("resource", resourceId).param("related", related).param("max", MAX_ATTEMPTS).update();
    }

    public Task get(String userId, String id) {
        return jdbc.sql("SELECT id,task_type,resource_id,status,attempts,max_attempts,error,created_at,updated_at FROM ai_mock_tasks WHERE id=:id AND user_id=:user")
            .param("id", id).param("user", userId).query((rs, row) -> api(rs)).optional().orElseThrow(AiMockTaskService::notFound);
    }

    public Task latest(String userId, String resourceId) {
        return jdbc.sql("SELECT id,task_type,resource_id,status,attempts,max_attempts,error,created_at,updated_at FROM ai_mock_tasks WHERE user_id=:user AND resource_id=:resource AND status <> 'COMPLETED' ORDER BY created_at DESC LIMIT 1")
            .param("user", userId).param("resource", resourceId).query((rs, row) -> api(rs)).optional().orElse(null);
    }

    @Transactional
    public ClaimedTask claim() {
        expireStale();
        String id = jdbc.sql("SELECT id FROM ai_mock_tasks WHERE (status='PENDING' OR (status='PROCESSING' AND locked_at < CURRENT_TIMESTAMP - INTERVAL '2' MINUTE)) AND attempts < max_attempts ORDER BY created_at LIMIT 1")
            .query(String.class).list().stream().findFirst().orElse(null);
        if (id == null) return null;
        String token = UUID.randomUUID().toString();
        int updated = jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=attempts+1,worker_token=:token,locked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND (status='PENDING' OR (status='PROCESSING' AND locked_at < CURRENT_TIMESTAMP - INTERVAL '2' MINUTE)) AND attempts < max_attempts")
            .param("id", id).param("token", token).update();
        if (updated == 0) return null;
        return jdbc.sql("SELECT id,user_id,task_type,resource_id,related_id,worker_token FROM ai_mock_tasks WHERE id=:id AND worker_token=:token")
            .param("id", id).param("token", token).query((rs, row) -> new ClaimedTask(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).single();
    }

    @Transactional
    public void complete(ClaimedTask task) {
        jdbc.sql("UPDATE ai_mock_tasks SET status='COMPLETED',error='',locked_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND worker_token=:token")
            .param("id", task.id()).param("token", task.workerToken()).update();
    }

    @Transactional
    public void fail(ClaimedTask task, RuntimeException exception) {
        String error = stableError(exception);
        jdbc.sql("UPDATE ai_mock_tasks SET status='FAILED',error=:error,locked_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND worker_token=:token")
            .param("id", task.id()).param("token", task.workerToken()).param("error", error).update();
    }

    @Transactional
    public void retry(String userId, String id) {
        int updated = jdbc.sql("UPDATE ai_mock_tasks SET status='PENDING',error='',locked_at=NULL,worker_token=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND status='FAILED' AND attempts < max_attempts")
            .param("id", id).param("user", userId).update();
        if (updated == 0) throw notFound();
    }

    @Transactional
    public void deleteForResource(String userId, String resourceId) {
        jdbc.sql("DELETE FROM ai_mock_tasks WHERE user_id=:user AND resource_id=:resource").param("user", userId).param("resource", resourceId).update();
    }

    private void expireStale() {
        jdbc.sql("UPDATE ai_mock_tasks SET status='FAILED',error='后台处理超时，请点击重试。',updated_at=CURRENT_TIMESTAMP WHERE status='PROCESSING' AND locked_at < CURRENT_TIMESTAMP - INTERVAL '2' MINUTE AND attempts >= max_attempts").update();
    }

    private static Task api(ResultSet rs) throws java.sql.SQLException {
        return new Task(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class));
    }

    public static String stableError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank() || message.matches(".*(?i)(sql|jdbc|database|password|secret|apikey|api-key|connection).*")) return "后台处理失败，请稍后重试。";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static NoSuchElementException notFound() { return new NoSuchElementException("任务不存在或无权访问。"); }

    public record ClaimedTask(String id, String userId, String taskType, String resourceId, String relatedId, String workerToken) {}
}

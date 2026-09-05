package com.interviewagent.ai;

import static com.interviewagent.ai.AiTaskApi.Task;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiMockTaskService {
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ThreadLocal<ClaimedTask> current = new ThreadLocal<>();

    public AiMockTaskService(JdbcClient jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transaction=new TransactionTemplate(manager); }

    public void execute(ClaimedTask task,Runnable work) {
        current.set(task);
        org.slf4j.MDC.put("taskId",task.id());
        org.slf4j.MDC.put("sessionId",task.resourceId());
        try { check(); work.run(); } finally { current.remove(); org.slf4j.MDC.remove("taskId"); org.slf4j.MDC.remove("sessionId"); }
    }

    public void expireVoice(String user,String id) {
        jdbc.sql("UPDATE ai_mock_interviews SET status='TIME_EXPIRED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND status='RUNNING' AND expires_at<=CURRENT_TIMESTAMP")
            .param("id",id).param("user",user).update();
        if (jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE id=:id AND user_id=:user AND status<>'RUNNING'").param("id",id).param("user",user).query(Integer.class).single()>0) cancelForResource(user,id);
    }

    private String table(ClaimedTask task) { return task.taskType().startsWith("AI_")?"ai_mock_interviews":"mock_interviews"; }

    private void running(ClaimedTask task,boolean lock) {
        String state=jdbc.sql("SELECT status FROM "+table(task)+" WHERE id=:id AND user_id=:user"+(lock?" FOR UPDATE":""))
            .param("id",task.resourceId()).param("user",task.userId()).query(String.class).optional().orElse("");
        if (!state.equals("RUNNING")) {
            if(!lock) cancelForResource(task.userId(),task.resourceId());
            throw new IllegalStateException("模拟已结束或超时，无法继续处理。");
        }
        if (task.taskType().startsWith("AI_") && jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE id=:id AND expires_at>CURRENT_TIMESTAMP").param("id",task.resourceId()).query(Integer.class).single()==0)
            throw new IllegalStateException("模拟已结束或超时，无法继续处理。");
    }

    public void check() {
        ClaimedTask task=current.get();
        if (task==null) throw new IllegalStateException("后台任务租约已失效。");
        if (task.taskType().startsWith("AI_")) expireVoice(task.userId(),task.resourceId());
        running(task,false);
        if (jdbc.sql("UPDATE ai_mock_tasks SET locked_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND resource_id=:resource AND task_type=:type AND worker_token=:token AND status='PROCESSING' AND locked_at>CURRENT_TIMESTAMP - INTERVAL '2' MINUTE")
            .param("id",task.id()).param("user",task.userId()).param("resource",task.resourceId()).param("type",task.taskType()).param("token",task.workerToken()).update()!=1) throw new IllegalStateException("后台任务租约已失效。");
    }

    public void write(Runnable write) {
        check();
        transaction.executeWithoutResult(status -> {
            ClaimedTask task=current.get();
            running(task,true);
            String token=jdbc.sql("SELECT worker_token FROM ai_mock_tasks WHERE id=:id AND status='PROCESSING' AND locked_at>CURRENT_TIMESTAMP - INTERVAL '2' MINUTE FOR UPDATE")
                .param("id",task.id()).query(String.class).optional().orElse("");
            if (!task.workerToken().equals(token)) throw new IllegalStateException("后台任务租约已失效。");
            write.run();
        });
    }

    public boolean hasActive(String user,String resource) {
        return jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE user_id=:user AND resource_id=:id AND status IN ('PENDING','PROCESSING')").param("user",user).param("id",resource).query(Integer.class).single()>0;
    }

    public void cancelForResource(String user,String resource) {
        // Completed means no more work; preserve the public four-state task API.
        jdbc.sql("UPDATE ai_mock_tasks SET status='COMPLETED',worker_token=NULL,locked_at=NULL,error='',updated_at=CURRENT_TIMESTAMP WHERE user_id=:user AND resource_id=:id AND status<>'COMPLETED'")
            .param("user",user).param("id",resource).update();
    }

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
        String id = jdbc.sql("SELECT id FROM ai_mock_tasks WHERE ((status='PENDING' AND available_at<=CURRENT_TIMESTAMP) OR (status='PROCESSING' AND locked_at < CURRENT_TIMESTAMP - INTERVAL '2' MINUTE)) AND attempts < max_attempts ORDER BY available_at,created_at LIMIT 1")
            .query(String.class).list().stream().findFirst().orElse(null);
        if (id == null) return null;
        String token = UUID.randomUUID().toString();
        int updated = jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=attempts+1,worker_token=:token,locked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND ((status='PENDING' AND available_at<=CURRENT_TIMESTAMP) OR (status='PROCESSING' AND locked_at < CURRENT_TIMESTAMP - INTERVAL '2' MINUTE)) AND attempts < max_attempts")
            .param("id", id).param("token", token).update();
        if (updated == 0) return null;
        return jdbc.sql("SELECT id,user_id,task_type,resource_id,related_id,worker_token FROM ai_mock_tasks WHERE id=:id AND worker_token=:token")
            .param("id", id).param("token", token).query((rs, row) -> new ClaimedTask(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).single();
    }

    @Transactional
    public void complete(ClaimedTask task) {
        jdbc.sql("UPDATE ai_mock_tasks SET status='COMPLETED',error='',locked_at=NULL,worker_token=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND worker_token=:token AND status='PROCESSING' AND locked_at>CURRENT_TIMESTAMP - INTERVAL '2' MINUTE")
            .param("id", task.id()).param("token", task.workerToken()).update();
    }

    @Transactional
    public void fail(ClaimedTask task, RuntimeException exception) {
        String error = stableError(exception);
        boolean retryable=exception instanceof SimulationException e && e.retryable();
        int attempts=jdbc.sql("SELECT attempts FROM ai_mock_tasks WHERE id=:id").param("id",task.id()).query(Integer.class).optional().orElse(0);
        jdbc.sql("UPDATE ai_mock_tasks SET status=CASE WHEN :retry AND attempts<max_attempts THEN 'PENDING' ELSE 'FAILED' END,error=:error,available_at=:available,locked_at=NULL,worker_token=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND worker_token=:token AND status='PROCESSING' AND locked_at>CURRENT_TIMESTAMP - INTERVAL '2' MINUTE")
            .param("id",task.id()).param("token",task.workerToken()).param("error",error).param("retry",retryable).param("available",OffsetDateTime.now().plusSeconds(attempts<=1?5:15)).update();
    }

    @Transactional(noRollbackFor = IllegalStateException.class)
    public void retry(String userId, String id) {
        Task existing=get(userId,id);
        ClaimedTask task=new ClaimedTask(id,userId,existing.taskType(),existing.resourceId(),"","");
        if(task.taskType().startsWith("AI_")) expireVoice(userId,task.resourceId());
        running(task,true);
        int updated = jdbc.sql("UPDATE ai_mock_tasks SET status='PENDING',attempts=0,error='',locked_at=NULL,worker_token=NULL,available_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND status='FAILED'")
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
        return exception instanceof SimulationException ? exception.getMessage() : "后台处理失败，请稍后重试。";
    }

    private static NoSuchElementException notFound() { return new NoSuchElementException("任务不存在或无权访问。"); }

    public record ClaimedTask(String id, String userId, String taskType, String resourceId, String relatedId, String workerToken) {}
}

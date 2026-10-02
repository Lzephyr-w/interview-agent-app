package com.interviewagent.aimock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.AiMockTaskService;
import com.interviewagent.ai.AiMockTaskService.ClaimedTask;
import com.interviewagent.ai.AiTaskApi.Task;
import com.interviewagent.ai.SimulationContract;
import com.interviewagent.ai.SimulationMaterials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewPackagePreparationService {
    // Bump this version when voice planning rules change.
    static final String GENERATION_VERSION = "simulation.v1:voice-plan-only-v3";
    public static final String TASK_TYPE = "PACKAGE_VOICE_PLAN";
    private static final Logger log = LoggerFactory.getLogger(InterviewPackagePreparationService.class);
    private final JdbcClient jdbc;
    private final SimulationMaterials materials;
    private final AiMockQuestionAgent questions;
    private final AiMockTaskService tasks;
    private final ObjectMapper json;

    public InterviewPackagePreparationService(JdbcClient jdbc, SimulationMaterials materials, AiMockQuestionAgent questions, AiMockTaskService tasks, ObjectMapper json) {
        this.jdbc=jdbc; this.materials=materials; this.questions=questions; this.tasks=tasks; this.json=json;
    }

    @Transactional
    public void queue(String user, String packageId) {
        try { ensure(user,packageId); }
        catch (IllegalArgumentException error) {
            // Saving materials remains possible even when they exceed the mock interview input budget.
            log.info("ai_mock_timing stage=package_plan_unavailable packageId={} reason=material_limit",packageId);
        }
    }

    @Transactional
    public Preparation ensure(String user, String packageId) {
        long started=System.nanoTime();
        jdbc.sql("SELECT id FROM interview_packages WHERE id=:id AND user_id=:user FOR UPDATE")
            .param("id",packageId).param("user",user).query(String.class).optional().orElseThrow(InterviewPackagePreparationService::notFound);
        String snapshot=materials.capture(user,packageId).toString();
        String fingerprint;
        try { fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((GENERATION_VERSION+"\n"+snapshot).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        Preparation preparation=jdbc.sql("SELECT id,material_snapshot,generated_result FROM ai_mock_package_preparations WHERE interview_package_id=:package AND user_id=:user AND material_fingerprint=:fingerprint AND generation_version=:version")
            .param("package",packageId).param("user",user).param("fingerprint",fingerprint).param("version",GENERATION_VERSION)
            .query((rs,row)->new Preparation(rs.getString(1),rs.getString(2),rs.getString(3))).optional().orElse(null);
        boolean reused=preparation!=null;
        if (preparation==null) {
            preparation=new Preparation(UUID.randomUUID().toString(),snapshot,null);
            jdbc.sql("INSERT INTO ai_mock_package_preparations(id,user_id,interview_package_id,material_fingerprint,generation_version,material_snapshot) VALUES(:id,:user,:package,:fingerprint,:version,:snapshot)")
                .param("id",preparation.id()).param("user",user).param("package",packageId).param("fingerprint",fingerprint).param("version",GENERATION_VERSION).param("snapshot",snapshot).update();
        }
        if (preparation.generatedResult()==null) tasks.enqueue(user,TASK_TYPE,preparation.id(),null);
        log.info("ai_mock_timing stage=package_plan_lookup packageId={} preparationId={} reused={} ready={} elapsed_ms={}",packageId,preparation.id(),reused,preparation.generatedResult()!=null,(System.nanoTime()-started)/1_000_000);
        return preparation;
    }

    Preparation get(String user, String id) {
        return jdbc.sql("SELECT id,material_snapshot,generated_result FROM ai_mock_package_preparations WHERE id=:id AND user_id=:user")
            .param("id",id).param("user",user).query((rs,row)->new Preparation(rs.getString(1),rs.getString(2),rs.getString(3))).optional().orElseThrow(InterviewPackagePreparationService::notFound);
    }

    java.util.List<AiMockQuestionAgent.PlanItem> planned(Preparation preparation, String sessionId) {
        UUID seed=UUID.fromString(sessionId);
        try {
            var result=json.readTree(preparation.generatedResult());
            var snapshot=json.readTree(preparation.materialSnapshot());
            // Existing waiting sessions may still reference the previous plan-and-first cache.
            if (result.has("firstQuestion")) return questions.validatePlanAndFirst(result,snapshot).plan();
            return questions.selectPlan(result,snapshot,new java.util.Random(seed.getMostSignificantBits()^seed.getLeastSignificantBits()));
        }
        catch (Exception error) { throw SimulationContract.retryableInvalid(); }
    }

    Task task(String user, String id) {
        get(user,id);
        return jdbc.sql("SELECT id FROM ai_mock_tasks WHERE user_id=:user AND resource_id=:id AND task_type=:type")
            .param("user",user).param("id",id).param("type",TASK_TYPE).query(String.class).optional().map(taskId->tasks.get(user,taskId)).orElse(null);
    }

    public void processTask(ClaimedTask task) {
        tasks.execute(task,()-> {
            Preparation preparation=get(task.userId(),task.resourceId());
            if (preparation.generatedResult()!=null) return;
            com.fasterxml.jackson.databind.JsonNode planned;
            int attempt=tasks.get(task.userId(),task.id()).attempts();
            try { planned=questions.planOnly(json.readTree(preparation.materialSnapshot()),attempt<=1?3:1); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw SimulationContract.invalid(); }
            long started=System.nanoTime();
            tasks.write(()->jdbc.sql("UPDATE ai_mock_package_preparations SET generated_result=:result,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND generated_result IS NULL")
                .param("result",json.valueToTree(planned).toString()).param("id",preparation.id()).param("user",task.userId()).update());
            log.info("ai_mock_timing stage=package_plan_commit preparationId={} elapsed_ms={}",preparation.id(),(System.nanoTime()-started)/1_000_000);
        });
    }

    @Transactional
    public void deleteTasks(String user, String packageId) {
        jdbc.sql("SELECT id FROM interview_packages WHERE id=:id AND user_id=:user FOR UPDATE")
            .param("id",packageId).param("user",user).query(String.class).optional().orElseThrow(InterviewPackagePreparationService::notFound);
        for (String id:jdbc.sql("SELECT id FROM ai_mock_package_preparations WHERE interview_package_id=:package AND user_id=:user FOR UPDATE")
            .param("package",packageId).param("user",user).query(String.class).list()) tasks.deleteForResource(user,id);
    }

    private static NoSuchElementException notFound() { return new NoSuchElementException("资源不存在或无权访问。"); }
    public record Preparation(String id, String materialSnapshot, String generatedResult) {}
}

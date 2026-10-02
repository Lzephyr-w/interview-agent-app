package com.interviewagent.aimock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.*;
import com.interviewagent.ai.storage.AiAudioStorage;
import com.interviewagent.ai.storage.AudioTranscriptionService;
import com.interviewagent.material.MaterialService;
import com.interviewagent.material.InterviewPackage;
import com.interviewagent.material.MaterialRequests.InterviewPackageRequest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"SUPABASE_URL=https://example.supabase.co","spring.datasource.url=jdbc:h2:mem:package-preparations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.default-schema=PUBLIC","spring.flyway.schemas=PUBLIC","spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class InterviewPackagePreparationTest {
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired MaterialService materials;
    @Autowired InterviewPackagePreparationService preparations;
    @Autowired AiMockInterviewService voice;
    @Autowired AiMockTaskService tasks;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockBean AiMockTaskWorker worker;
    @MockBean AgentPythonClient agent;
    @MockBean AiAudioStorage storage;
    @MockBean AudioTranscriptionService transcription;

    @BeforeEach void setup() {
        jdbc.sql("DELETE FROM ai_mock_tasks").update();
        jdbc.sql("DELETE FROM interview_packages").update();
        when(agent.simulate(eq("VOICE_PLAN_ONLY"),anyMap())).thenAnswer(call->plan(json.valueToTree(call.getArgument(1)).path("materials")));
        when(agent.simulate(eq("VOICE_QUESTION"),anyMap())).thenAnswer(call->{
            var slot=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(call.getArgument(1)).path("slot").deepCopy();
            String angle=slot.path("angle").asText(); slot.remove(List.of("order","angle"));
            return slot.put("questionText","请解释"+angle+"的实现原理？");
        });
    }

    InterviewPackage pack(String user) {
        String id=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO job_descriptions(id,user_id,company,role,content) VALUES(:id,:user,'公司','前端','岗位职责')").param("id",id).param("user",user).update();
        jdbc.sql("INSERT INTO resume_files(id,user_id,original_filename,content_type,size_bytes,object_path,parsed_status,parsed_text) VALUES(:id,:user,'resume.pdf','application/pdf',1,:id,'READY','项目甲 项目乙')").param("id",id).param("user",user).update();
        List<String> cards=new ArrayList<>();
        for (String name:List.of("项目甲","项目乙")) {
            String card=UUID.randomUUID().toString(); cards.add(card);
            jdbc.sql("INSERT INTO project_evidence_cards(id,user_id,project_name,technology_stack,project_description_and_responsibilities,project_highlights) VALUES(:id,:user,:name,'React','负责开发','性能优化')")
                .param("id",card).param("user",user).param("name",name).update();
        }
        return materials.createInterviewPackage(user,new InterviewPackageRequest(null,null,"一面",id,id,cards));
    }

    JsonNode plan(JsonNode snapshot) {
        var result=json.createObjectNode(); var slots=result.putArray("plan");
        for (int i=1;i<=10;i++) {
            var item=slots.addObject().put("order",i).put("type",i<=5?"FUNDAMENTAL":i<=9?"PROJECT":"SCENARIO")
                .put("competency","能力"+i).put("projectName",i>=6&&i<=9?snapshot.path("cards").get((i-6)%2).path("projectName").asText():"").put("technology","技术"+i).put("angle","角度"+i);
            var options=item.putArray("alternatives");
            for (int option=1;option<=2;option++) options.addObject().put("competency","能力"+i+"切入"+option).put("angle","角度"+i+"切入"+option);
        }
        return result;
    }

    void complete(AiMockTaskService.ClaimedTask task) {
        preparations.processTask(task);
        voice.publishPreparation(task.userId(),task.resourceId());
        tasks.complete(task);
    }

    @Test void savingOnlyQueuesAndUnchangedUpdateDeduplicates() {
        var pack=pack("save");
        verifyNoInteractions(agent);
        materials.updateInterviewPackage("save",pack.id(),new InterviewPackageRequest(null,null,"一面",pack.resumeFileId(),pack.jobDescriptionId(),pack.evidenceCardIds()));
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_package_preparations").query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks").query(Integer.class).single());
        assertEquals("PENDING",tasks.latest("save",preparations.ensure("save",pack.id()).id()).status());
        verifyNoInteractions(agent);
    }

    @Test void readyPlanIsCopiedWithoutQuestionsAndFirstIsGeneratedOnlyAfterBegin() {
        var pack=pack("ready"); complete(tasks.claim()); clearInvocations(agent);
        var first=voice.prepare("ready",new AiMockInterviewApi.StartRequest(pack.id()));
        var second=voice.create("ready",new AiMockInterviewApi.StartRequest(pack.id()));
        assertNull(first.currentQuestion()); assertNull(second.currentQuestion());
        assertEquals(10,first.totalQuestions()); assertNull(first.task());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE task_type='AI_PLAN'").query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE task_type='AI_PREPARE_NEXT'").query(Integer.class).single());
        assertEquals(-1,jdbc.sql("SELECT current_question_index FROM ai_mock_interviews WHERE id=:id").param("id",first.id()).query(Integer.class).single());
        assertEquals("AI_FIRST",voice.begin("ready",first.id()).task().taskType());
        voice.begin("ready",first.id());
        assertEquals(0,jdbc.sql("SELECT current_question_index FROM ai_mock_interviews WHERE id=:id").param("id",first.id()).query(Integer.class).single());
        verifyNoInteractions(agent);
        for (int i=0;i<2;i++) { var task=tasks.claim(); assertEquals("AI_FIRST",task.taskType()); voice.processTask(task); tasks.complete(task); }
        assertNotNull(voice.get("ready",first.id()).currentQuestion());
        assertNotNull(voice.get("ready",second.id()).currentQuestion());
        assertNotEquals(voice.get("ready",first.id()).currentQuestion().id(),voice.get("ready",second.id()).currentQuestion().id());
        verify(agent,times(2)).simulate(eq("VOICE_QUESTION"),anyMap());
    }

    @Test void pendingSessionsShareOneTaskAndPollingPublishesOnce() {
        var pack=pack("pending");
        var first=voice.prepare("pending",new AiMockInterviewApi.StartRequest(pack.id()));
        var second=voice.prepare("pending",new AiMockInterviewApi.StartRequest(pack.id()));
        assertNull(first.currentQuestion()); assertEquals(first.task().id(),second.task().id());
        assertEquals(InterviewPackagePreparationService.TASK_TYPE,first.task().taskType());
        var task=tasks.claim(); preparations.processTask(task); tasks.complete(task);
        assertNull(voice.get("pending",first.id()).currentQuestion());
        assertNull(voice.get("pending",second.id()).currentQuestion());
        voice.get("pending",first.id()); voice.publishPreparation("pending",task.resourceId());
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE question_plan IS NOT NULL").query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions").query(Integer.class).single());
        verify(agent,times(1)).simulate(eq("VOICE_PLAN_ONLY"),anyMap());
    }

    @Test void beginBeforePlanIsReadyQueuesOneFirstTaskAfterPublication() {
        var pack=pack("early");
        var session=voice.prepare("early",new AiMockInterviewApi.StartRequest(pack.id()));
        assertEquals(InterviewPackagePreparationService.TASK_TYPE,voice.begin("early",session.id()).task().taskType());
        complete(tasks.claim());
        voice.publishPreparation("early",session.task().resourceId());
        assertEquals("AI_FIRST",voice.get("early",session.id()).task().taskType());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE task_type='AI_FIRST'").query(Integer.class).single());
        verify(agent,never()).simulate(eq("VOICE_QUESTION"),anyMap());
        var first=tasks.claim(); voice.processTask(first); tasks.complete(first);
        assertNotNull(voice.get("early",session.id()).currentQuestion());
    }

    @Test void invalidFirstRetriesImmediatelyWithoutExposingAnErrorUntilExhausted() {
        var pack=pack("first-retry"); complete(tasks.claim());
        var session=voice.prepare("first-retry",new AiMockInterviewApi.StartRequest(pack.id()));
        voice.begin("first-retry",session.id());
        when(agent.simulate(eq("VOICE_QUESTION"),anyMap())).thenThrow(new SimulationException("INVALID_MODEL_OUTPUT",true));
        for (int attempt=1;attempt<=3;attempt++) {
            var task=tasks.claim(); assertNotNull(task); assertEquals("AI_FIRST",task.taskType());
            tasks.fail(task,assertThrows(SimulationException.class,()->voice.processTask(task)));
            var detail=voice.get("first-retry",session.id()).task();
            assertEquals(attempt,detail.attempts());
            assertEquals(attempt<3?"PENDING":"FAILED",detail.status());
            if (attempt<3) {
                assertEquals("",detail.error());
                assertFalse(jdbc.sql("SELECT error FROM ai_mock_tasks WHERE id=:id").param("id",task.id()).query(String.class).single().isBlank());
            } else assertEquals("本次内容未能生成，请重试。",detail.error());
        }
        assertNull(tasks.claim());
        assertNull(voice.get("first-retry",session.id()).currentQuestion());
    }

    @Test void perSessionFocusAndOrderVaryButPollingAndFirstRetryKeepTheFrozenPlan() {
        var pack=pack("variation"); complete(tasks.claim());
        var preparation=preparations.ensure("variation",pack.id());
        assertFalse(preparation.generatedResult().contains("firstQuestion"));
        var one=preparations.planned(preparation,"00000000-0000-0000-0000-000000000001");
        var two=preparations.planned(preparation,"00000000-0000-0000-0000-000000000002");
        assertNotEquals(one,two);
        assertEquals(one,preparations.planned(preparation,"00000000-0000-0000-0000-000000000001"));
        for (var plan:List.of(one,two)) {
            assertEquals(10,plan.stream().map(AiMockQuestionAgent.PlanItem::competency).distinct().count());
            assertEquals(5,plan.stream().filter(slot->slot.type().equals("FUNDAMENTAL")).count());
            assertEquals(4,plan.stream().filter(slot->slot.type().equals("PROJECT")).count());
            for (int i=6;i<=8;i++) assertNotEquals(plan.get(i-1).projectName(),plan.get(i).projectName());
        }
        var session=voice.prepare("variation",new AiMockInterviewApi.StartRequest(pack.id()));
        String frozen=jdbc.sql("SELECT question_plan FROM ai_mock_interviews WHERE id=:id").param("id",session.id()).query(String.class).single();
        voice.get("variation",session.id());
        voice.begin("variation",session.id());
        when(agent.simulate(eq("VOICE_QUESTION"),anyMap())).thenThrow(new SimulationException("MODEL_TIMEOUT"));
        var first=tasks.claim(); tasks.fail(first,assertThrows(SimulationException.class,()->voice.processTask(first)));
        voice.get("variation",session.id());
        assertEquals(frozen,jdbc.sql("SELECT question_plan FROM ai_mock_interviews WHERE id=:id").param("id",session.id()).query(String.class).single());
        assertNull(voice.get("variation",session.id()).currentQuestion());
    }

    @Test void oldTaskCannotOverwriteNewVersionAndWaitingSessionKeepsSnapshot() {
        var pack=pack("versions");
        var oldSession=voice.prepare("versions",new AiMockInterviewApi.StartRequest(pack.id()));
        var oldTask=tasks.claim();
        materials.updateInterviewPackage("versions",pack.id(),new InterviewPackageRequest(null,null,"二面",pack.resumeFileId(),pack.jobDescriptionId(),pack.evidenceCardIds()));
        var newSession=voice.prepare("versions",new AiMockInterviewApi.StartRequest(pack.id()));
        var newTask=tasks.claim(); assertNotEquals(oldTask.resourceId(),newTask.resourceId());
        complete(newTask); complete(oldTask);
        assertEquals("一面",voice.get("versions",oldSession.id()).interviewRound());
        assertEquals("二面",voice.get("versions",newSession.id()).interviewRound());
        assertEquals(newTask.resourceId(),preparations.ensure("versions",pack.id()).id());
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM ai_mock_package_preparations WHERE generated_result IS NOT NULL").query(Integer.class).single());
        verify(agent,times(2)).simulate(eq("VOICE_PLAN_ONLY"),anyMap());
    }

    @Test void independentMaterialEditsAndRuleVersionInvalidateDraft() {
        var pack=pack("edits"); complete(tasks.claim());
        String initial=preparations.ensure("edits",pack.id()).id();
        jdbc.sql("UPDATE job_descriptions SET content='新岗位要求' WHERE id=:id").param("id",pack.jobDescriptionId()).update();
        String jd=voice.prepare("edits",new AiMockInterviewApi.StartRequest(pack.id())).task().resourceId(); assertNotEquals(initial,jd);
        jdbc.sql("UPDATE project_evidence_cards SET project_highlights='新项目成果' WHERE id=:id").param("id",pack.evidenceCardIds().getFirst()).update();
        String card=preparations.ensure("edits",pack.id()).id(); assertNotEquals(jd,card);
        jdbc.sql("UPDATE resume_files SET parsed_text='新的真实简历经历' WHERE id=:id").param("id",pack.resumeFileId()).update();
        String resume=preparations.ensure("edits",pack.id()).id(); assertNotEquals(card,resume);
        jdbc.sql("UPDATE ai_mock_package_preparations SET generation_version='old-rules' WHERE id=:id").param("id",resume).update();
        assertNotEquals(resume,preparations.ensure("edits",pack.id()).id());
    }

    @Test void failedDraftUsesBoundedRetryAndExistingRetryEndpoint() throws Exception {
        var pack=pack("failure");
        var session=voice.prepare("failure",new AiMockInterviewApi.StartRequest(pack.id()));
        when(agent.simulate(eq("VOICE_PLAN_ONLY"),anyMap())).thenThrow(new SimulationException("MODEL_TIMEOUT"));
        for (int attempt=1;attempt<=3;attempt++) {
            jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP").update();
            var task=tasks.claim();
            tasks.fail(task,assertThrows(SimulationException.class,()->preparations.processTask(task)));
            assertEquals(attempt,tasks.get("failure",task.id()).attempts());
        }
        assertEquals("FAILED",voice.get("failure",session.id()).task().status());
        assertNull(voice.get("failure",session.id()).currentQuestion());
        preparations.ensure("failure",pack.id()); assertNull(tasks.claim());
        mvc.perform(post("/api/v1/ai-mock-tasks/{id}/retry",session.task().id()).with(jwt().jwt(t->t.subject("other")))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/ai-mock-tasks/{id}/retry",session.task().id()).with(jwt().jwt(t->t.subject("failure")))).andExpect(status().isAccepted());
        when(agent.simulate(eq("VOICE_PLAN_ONLY"),anyMap())).thenAnswer(call->plan(json.valueToTree(call.getArgument(1)).path("materials")));
        complete(tasks.claim()); assertNull(voice.get("failure",session.id()).currentQuestion());
        assertNull(voice.get("failure",session.id()).task());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_package_preparations").query(Integer.class).single());
    }

    @Test void planRetrySimplifiesOptionalFocusesAndFreezesAUsablePlanWithoutGeneratingFirst() {
        var pack=pack("base-retry");
        var session=voice.prepare("base-retry",new AiMockInterviewApi.StartRequest(pack.id()));
        when(agent.simulate(eq("VOICE_PLAN_ONLY"),anyMap())).thenAnswer(call->{
            var input=json.valueToTree(call.getArgument(1));
            if (input.path("focusCount").asInt()==3) throw new SimulationException("INVALID_MODEL_OUTPUT",true);
            assertEquals(1,input.path("focusCount").asInt());
            var result=plan(input.path("materials"));
            for (var item:result.path("plan")) ((com.fasterxml.jackson.databind.node.ObjectNode)item).putArray("alternatives");
            // The four grounded project slots can be reordered without another model request.
            ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("plan").get(5)).put("projectName","项目甲");
            ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("plan").get(6)).put("projectName","项目甲");
            ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("plan").get(7)).put("projectName","项目乙");
            ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("plan").get(8)).put("projectName","项目乙");
            return result;
        });
        var first=tasks.claim();
        tasks.fail(first,assertThrows(SimulationException.class,()->preparations.processTask(first)));
        jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",first.id()).update();
        complete(tasks.claim());
        var ready=voice.get("base-retry",session.id());
        assertNull(ready.task()); assertNull(ready.currentQuestion());
        var stored=preparations.get("base-retry",first.resourceId());
        try {
            var slots=json.readTree(stored.generatedResult()).path("plan");
            for (int i=6;i<=8;i++) assertNotEquals(slots.get(i-1).path("projectName").asText(),slots.get(i).path("projectName").asText());
        } catch (Exception error) { throw new AssertionError(error); }
        assertEquals(10,preparations.planned(stored,session.id()).size());
        assertTrue(json.valueToTree(preparations.planned(stored,session.id())).toString().contains("PROJECT"));
        verify(agent,times(2)).simulate(eq("VOICE_PLAN_ONLY"),anyMap());
        verify(agent,never()).simulate(eq("VOICE_QUESTION"),anyMap());
    }

    @Test void invalidModelOutputAndReplacedLeaseCannotWriteResults() {
        var pack=pack("lease"); var task=tasks.claim();
        when(agent.simulate(eq("VOICE_PLAN_ONLY"),anyMap())).thenAnswer(call->{
            var invalid=plan(json.valueToTree(call.getArgument(1)).path("materials"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("plan").get(0).path("alternatives").get(0)).put("competency","能力1"); return invalid;
        });
        assertThrows(SimulationException.class,()->preparations.processTask(task));
        assertNull(preparations.get("lease",task.resourceId()).generatedResult());
        when(agent.simulate(eq("VOICE_PLAN_ONLY"),anyMap())).thenAnswer(call->{
            jdbc.sql("UPDATE ai_mock_tasks SET worker_token='replacement' WHERE id=:id").param("id",task.id()).update();
            return plan(json.valueToTree(call.getArgument(1)).path("materials"));
        });
        assertThrows(IllegalStateException.class,()->preparations.processTask(task));
        assertNull(preparations.get("lease",task.resourceId()).generatedResult());
    }

    @Test void deletedPackageCannotBeWrittenAndForeignUserCannotAccess() throws Exception {
        var pack=pack("delete"); var task=tasks.claim();
        mvc.perform(post("/api/v1/ai-mock-interviews/prepare").with(jwt().jwt(t->t.subject("other"))).contentType("application/json").content("{\"interviewPackageId\":\""+pack.id()+"\"}")).andExpect(status().isNotFound());
        assertThrows(NoSuchElementException.class,()->preparations.get("other",task.resourceId()));
        assertThrows(NoSuchElementException.class,()->materials.deleteInterviewPackage("other",pack.id()));
        when(agent.simulate(eq("VOICE_PLAN_ONLY"),anyMap())).thenAnswer(call->{
            materials.deleteInterviewPackage("delete",pack.id()); return plan(json.valueToTree(call.getArgument(1)).path("materials"));
        });
        assertThrows(IllegalStateException.class,()->preparations.processTask(task));
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_package_preparations").query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks").query(Integer.class).single());
    }

    @Test void expiredWaitingSessionDoesNotCancelSharedPreparation() {
        var pack=pack("expire");
        var expired=voice.prepare("expire",new AiMockInterviewApi.StartRequest(pack.id()));
        var active=voice.prepare("expire",new AiMockInterviewApi.StartRequest(pack.id()));
        jdbc.sql("UPDATE ai_mock_interviews SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' MINUTE WHERE id=:id").param("id",expired.id()).update();
        assertEquals("TIME_EXPIRED",voice.get("expire",expired.id()).status());
        complete(tasks.claim());
        assertNull(voice.get("expire",expired.id()).currentQuestion());
        assertNull(voice.get("expire",active.id()).currentQuestion());
        assertNotNull(jdbc.sql("SELECT question_plan FROM ai_mock_interviews WHERE id=:id").param("id",active.id()).query(String.class).single());
    }

    @Test void preparationHasLowerQueuePriorityThanActiveInterviewTasks() {
        pack("priority");
        tasks.enqueue("priority","AI_PREPARE_NEXT",UUID.randomUUID().toString(),"question");
        tasks.enqueue("priority","AI_AUDIO",UUID.randomUUID().toString(),"audio");
        assertEquals("AI_AUDIO",tasks.claim().taskType());
        assertEquals("AI_PREPARE_NEXT",tasks.claim().taskType());
        assertEquals(InterviewPackagePreparationService.TASK_TYPE,tasks.claim().taskType());
    }

    @Test void concurrentFirstPrepareCreatesOneDraftAndTask() throws Exception {
        var pack=pack("concurrent");
        preparations.deleteTasks("concurrent",pack.id());
        jdbc.sql("DELETE FROM ai_mock_package_preparations WHERE interview_package_id=:id").param("id",pack.id()).update();
        var gate=new java.util.concurrent.CountDownLatch(1);
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<AiMockInterviewApi.Session> prepare=()->{gate.await();return voice.prepare("concurrent",new AiMockInterviewApi.StartRequest(pack.id()));};
            var first=executor.submit(prepare); var second=executor.submit(prepare); gate.countDown();
            assertEquals(first.get(5,java.util.concurrent.TimeUnit.SECONDS).task().id(),second.get(5,java.util.concurrent.TimeUnit.SECONDS).task().id());
        }
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_package_preparations").query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks").query(Integer.class).single());
    }

    @Test void rolledBackPackageDoesNotLeavePreparationOrTask() {
        assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            pack("rollback"); throw new IllegalStateException("cancel save");
        }));
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM interview_packages").query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_package_preparations").query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks").query(Integer.class).single());
        verifyNoInteractions(agent);
    }

    @Test void savingOverBudgetMaterialsDoesNotRollbackPackage() {
        var pack=pack("budget");
        List<String> cards=new ArrayList<>(pack.evidenceCardIds());
        for (int i=0;i<29;i++) {
            String card=UUID.randomUUID().toString(); cards.add(card);
            jdbc.sql("INSERT INTO project_evidence_cards(id,user_id,project_name,technology_stack,project_description_and_responsibilities,project_highlights) VALUES(:id,'budget','其他项目','React','开发','成果')").param("id",card).update();
        }
        materials.updateInterviewPackage("budget",pack.id(),new InterviewPackageRequest(null,null,"二面",pack.resumeFileId(),pack.jobDescriptionId(),cards));
        assertEquals(31,materials.interviewPackage("budget",pack.id()).evidenceCardIds().size());
        assertThrows(IllegalArgumentException.class,()->voice.prepare("budget",new AiMockInterviewApi.StartRequest(pack.id())));
        verifyNoInteractions(agent);
    }
}

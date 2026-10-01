package com.interviewagent.aimock;

import static com.interviewagent.aimock.AiMockQuestionAgent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.ai.SimulationException;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import com.interviewagent.ai.AiMockTaskWorker;
import com.interviewagent.ai.storage.AiAudioStorage;
import com.interviewagent.ai.storage.AudioTranscriptionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "app.ai-mock-task.poll-ms=600000", "spring.datasource.url=jdbc:h2:mem:ai-mock-agent-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class AiMockQuestionAgentTest {
    @Autowired AiMockQuestionAgent agent;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired AiMockTaskWorker worker;
    @Autowired AiMockInterviewService voice;
    @Autowired com.interviewagent.ai.AiMockTaskService tasks;
    @Autowired com.interviewagent.ai.SimulationMaterials materials;
    @MockBean AgentPythonClient model;
    @MockBean ReviewModelClient reviewModel;
    @MockBean AiAudioStorage storage;
    @MockBean AudioTranscriptionService transcription;

    @org.junit.jupiter.api.AfterEach
    void noReviewModelCalls() { org.mockito.Mockito.verifyNoInteractions(reviewModel); }

    @Test
    void planContainsRequiredDifferentTypes() throws Exception {
        List<PlanItem> plan = agent.parsePlan(json.readTree(validPlan()),false);
        assertEquals(List.of("FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "PROJECT", "PROJECT", "PROJECT", "PROJECT", "SCENARIO"), plan.stream().map(PlanItem::type).toList());
        assertEquals(3, plan.stream().map(PlanItem::type).distinct().count());
    }

    @Test
    void planReordersRepeatedProjectSlotsWithoutDroppingValidation() throws Exception {
        var root=json.readTree(validPlan());
        var slots=(com.fasterxml.jackson.databind.node.ArrayNode)root.path("plan");
        ((com.fasterxml.jackson.databind.node.ObjectNode)slots.get(1)).put("technology","浏览器");
        ((com.fasterxml.jackson.databind.node.ObjectNode)slots.get(6)).put("projectName","订单平台");
        ((com.fasterxml.jackson.databind.node.ObjectNode)slots.get(8)).put("projectName","库存平台");
        assertThrows(RuntimeException.class,()->agent.parsePlan(root,false));
        var source=json.readTree("{\"resume\":\"订单平台 库存平台\",\"cards\":[],\"experienceAnchors\":[\"订单平台\",\"库存平台\"]}");
        when(model.simulate(eq("VOICE_PLAN"),anyMap())).thenReturn(root);
        var result=agent.planAndFirst(source);
        assertEquals(List.of("订单平台","库存平台","订单平台","库存平台"),
            result.plan().subList(5,9).stream().map(PlanItem::projectName).toList());
        assertEquals("浏览器如何调度微任务？",result.firstQuestion().questionText());
        assertNotEquals("浏览器",result.plan().get(1).technology());
        assertEquals(result.plan(),agent.parsePlan(json.readTree(agent.serialize(result.plan())),false));
    }

    @Test
    void strictPlanRejectsLegacySizeDistributionAndInvalidFields() throws Exception {
        var valid=json.readTree(validPlan());
        assertEquals(10,agent.parsePlan(valid,false).size());
        var three=valid.deepCopy();
        var array=(com.fasterxml.jackson.databind.node.ArrayNode)three.path("plan");
        while(array.size()>3) array.remove(array.size()-1);
        assertEquals(3,agent.parsePlan(three,true).size());
        assertThrows(RuntimeException.class,()->agent.parsePlan(three,false));
        var missingProject=valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)missingProject.path("plan").get(5)).put("projectName","");
        assertThrows(RuntimeException.class,()->agent.parsePlan(missingProject,false));
        var questionLikeAngle=valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)questionLikeAngle.path("plan").get(0)).put("angle","如何处理？");
        assertThrows(RuntimeException.class,()->agent.parsePlan(questionLikeAngle,false));
        for(String field:List.of("type","competency","order","technology","angle")) {
            var invalid=valid.deepCopy();
            var first=(com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("plan").get(0);
            if(field.equals("type")) first.put(field,"PROJECT");
            else if(field.equals("order")) first.put(field,"1");
            else first.put(field,"长".repeat(201));
            assertThrows(RuntimeException.class,()->agent.parsePlan(invalid,false));
        }
    }

    @Test
    void finalValidationRejectsFabricatedProjectDuplicateAndMetadataMismatch() throws Exception {
        var materials=json.readTree("{\"company\":\"公司\",\"role\":\"开发\",\"round\":\"一面\",\"jd\":\"岗位\",\"resume\":\"待补充\",\"cards\":[]}");
        var plan=json.readTree(validPlan());
        ((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("plan").get(5)).put("projectName","虚构项目");
        when(model.simulate(eq("VOICE_PLAN"),anyMap())).thenReturn(plan);
        assertThrows(RuntimeException.class,()->agent.plan(materials));
        PlanItem slot=new PlanItem(1,"FUNDAMENTAL","事件循环","","浏览器","调度");
        var good=json.readTree("{\"questionText\":\"浏览器如何调度微任务？\",\"type\":\"FUNDAMENTAL\",\"competency\":\"事件循环\",\"projectName\":\"\",\"technology\":\"浏览器\"}");
        for(String field:List.of("type","competency","projectName","technology","questionText")) {
            var bad=good.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)bad).put(field,field.equals("questionText")?"长".repeat(801):field.equals("projectName")?"!!!":"错误值");
            when(model.simulate(eq("VOICE_QUESTION"),anyMap())).thenReturn(bad);
            assertThrows(RuntimeException.class,()->agent.generate(materials,slot,List.of()));
        }
        when(model.simulate(eq("VOICE_QUESTION"),anyMap())).thenReturn(good);
        assertThrows(RuntimeException.class,()->agent.generate(materials,slot,List.of(new QuestionHistory("浏览器如何调度微任务？","FUNDAMENTAL","旧能力","","旧技术"))));
    }

    @Test
    void projectMayUseResumeExperienceAnchorWithoutEvidenceCard() throws Exception {
        var materials=json.readTree("{\"company\":\"汇量科技有限公司\",\"role\":\"视频剪辑岗位\",\"round\":\"一面\",\"jd\":\"岗位\",\"resume\":\"汇量科技 中康科技 个人自媒体运营\",\"cards\":[],\"experienceAnchors\":[\"汇量科技\",\"中康科技\",\"个人自媒体运营\",\"Loopit\"]}");
        var plan=json.readTree(validPlan());
        String[] anchors={"汇量科技","中康科技","个人自媒体运营","Loopit"};
        for(int i=0;i<anchors.length;i++) ((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("plan").get(5+i)).put("projectName",anchors[i]);
        when(model.simulate(eq("VOICE_PLAN"),anyMap())).thenReturn(plan);
        assertEquals(10,agent.plan(materials).size());
    }

    @Test
    void finalValidationRejectsLongOrCompoundQuestion() throws Exception {
        var materials=json.readTree("{\"company\":\"公司\",\"role\":\"开发\",\"round\":\"一面\",\"jd\":\"岗位\",\"resume\":\"待补充\",\"cards\":[]}");
        PlanItem slot=new PlanItem(1,"FUNDAMENTAL","事件循环","","浏览器","调度");
        for(String text:List.of("长".repeat(201),"请说明事件循环如何工作？再说明微任务如何执行？")) {
            when(model.simulate(eq("VOICE_QUESTION"),anyMap())).thenReturn(json.readTree("{\"questionText\":\""+text+"\",\"type\":\"FUNDAMENTAL\",\"competency\":\"事件循环\",\"projectName\":\"\",\"technology\":\"浏览器\"}"));
            assertThrows(RuntimeException.class,()->agent.generate(materials,slot,List.of()));
        }
        PlanItem projectSlot=new PlanItem(6,"PROJECT","性能优化","","React","瓶颈定位");
        when(model.simulate(eq("VOICE_QUESTION"),anyMap())).thenReturn(json.readTree("{\"questionText\":\"你如何定位订单平台的性能瓶颈？\",\"type\":\"PROJECT\",\"competency\":\"性能优化\",\"projectName\":\"\",\"technology\":\"React\"}"));
        assertThrows(RuntimeException.class,()->agent.generate(materials,projectSlot,List.of(),true));
    }

    @Test
    void planAcceptsEquivalentModelJson() throws Exception {
        var root = json.readTree(validPlan()).get("plan").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get(0)).put("order", "1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get(0)).put("type", "fundamental");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get(0)).put("projectName", "待补充");
        assertEquals("FUNDAMENTAL", agent.parsePlan(root,true).getFirst().type());
        assertEquals("", agent.parsePlan(root,true).getFirst().projectName());
    }

    @Test
    void sameProjectOrCompetencyIsRejected() {
        PlanItem plan = new PlanItem(7, "PROJECT", "性能验证", "订单平台", "React", "验证方法");
        List<QuestionHistory> history = List.of(new QuestionHistory("请说明订单平台的性能瓶颈。", "PROJECT", "瓶颈定位", "订单平台", "Vue"));
        assertEquals("连续使用同一项目", qualityError(new QuestionDraft("你如何验证优化效果？", "PROJECT", "性能验证", "订单平台", "React"), plan, history));
        assertEquals("考察能力点重复", qualityError(new QuestionDraft("你如何复盘性能结果？", "PROJECT", "瓶颈定位", "支付平台", "React"), new PlanItem(7, "PROJECT", "瓶颈定位", "支付平台", "React", "复盘"), history));
    }

    @Test
    void invalidPlanCreatesNeitherSessionNorQuestion() throws Exception {
        String packageId = packageFor("user-a");
        when(model.simulate(anyString(),anyMap())).thenReturn(json.readTree("{\"plan\":[]}"));
        MvcResult created = mockMvc.perform(post("/api/v1/ai-mock-interviews").with(jwt().jwt(token -> token.subject("user-a"))).contentType("application/json").content("{\"interviewPackageId\":\"" + packageId + "\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.task.status").value("PENDING")).andReturn();
        String taskId = json.readTree(created.getResponse().getContentAsString()).get("task").get("id").asText();
        awaitTask(taskId,1,"PENDING");
        retryNow(taskId,2,"PENDING"); retryNow(taskId,3,"FAILED");
        mockMvc.perform(get("/api/v1/ai-mock-tasks/{id}", taskId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("无效")));
        mockMvc.perform(get("/api/v1/ai-mock-tasks/{id}", taskId).with(jwt().jwt(token -> token.subject("user-b"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/ai-mock-tasks/{id}/retry", taskId).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
        worker.run();
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE user_id='user-a'").query(Integer.class).single());
        String sessionId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id").param("id", sessionId).query(Integer.class).single());
    }

    @Test
    void invalidQuestionJsonCreatesNeitherSessionNorQuestion() throws Exception {
        String packageId = packageFor("invalid-question-user");
        var invalid=json.readTree(validPlan());
        ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("firstQuestion")).put("competency","错误能力");
        when(model.simulate(eq("VOICE_PLAN"),anyMap())).thenReturn(invalid, json.readTree(validPlan()));
        MvcResult created = mockMvc.perform(post("/api/v1/ai-mock-interviews").with(jwt().jwt(token -> token.subject("invalid-question-user"))).contentType("application/json").content("{\"interviewPackageId\":\"" + packageId + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        String sessionId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String taskId = json.readTree(mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("invalid-question-user"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("task").path("id").asText();
        awaitTask(taskId,1,"PENDING");
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE id=:id AND question_plan IS NOT NULL").param("id", sessionId).query(Integer.class).single());
        retryNow(taskId,2,"COMPLETED");
        mockMvc.perform(get("/api/v1/ai-mock-tasks/{id}", taskId).with(jwt().jwt(token -> token.subject("invalid-question-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE user_id='invalid-question-user'").query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE user_id='invalid-question-user' AND question_plan IS NOT NULL").query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id").param("id", sessionId).query(Integer.class).single());
    }

    @Test
    void legacySessionWithoutPlanOrMetadataStillReads() throws Exception {
        String packageId = packageFor("legacy-user"), sessionId = UUID.randomUUID().toString(), questionId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,'legacy-user',:package,'旧公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)").param("id", sessionId).param("package", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'浏览器事件循环如何工作？','OPEN',0)").param("id", questionId).param("session", sessionId).update();
        mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("legacy-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalQuestions").value(3)).andExpect(jsonPath("$.currentQuestion.questionType").value("FUNDAMENTAL")).andExpect(jsonPath("$.currentQuestion.competency").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void expiredQuestionGetOnlyReadsAndDoesNotGenerate() throws Exception {
        String packageId = packageFor("expired-user"), sessionId = UUID.randomUUID().toString(), questionId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,'expired-user',:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id", sessionId).param("package", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order,answer_expires_at) VALUES(:id,:session,'过期题目','OPEN',0,CURRENT_TIMESTAMP - INTERVAL '1' MINUTE)")
            .param("id", questionId).param("session", sessionId).update();
        mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("expired-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion.id").value(questionId)).andExpect(jsonPath("$.currentQuestion.state").value("OPEN"));
        verify(model, never()).simulate(anyString(),anyMap());
    }

    @Test
    void questionCannotExpireBeforeAnswerStarts() throws Exception {
        String packageId = packageFor("not-started-user"), sessionId = UUID.randomUUID().toString(), questionId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,'not-started-user',:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id", sessionId).param("package", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order,answer_expires_at) VALUES(:id,:session,'尚未回答的首题','OPEN',0,CURRENT_TIMESTAMP - INTERVAL '1' MINUTE)")
            .param("id", questionId).param("session", sessionId).update();

        mockMvc.perform(post("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/expire", sessionId, questionId).with(jwt().jwt(token -> token.subject("not-started-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion.id").value(questionId));
        assertEquals("OPEN", jdbc.sql("SELECT state FROM ai_mock_interview_questions WHERE id=:id").param("id", questionId).query(String.class).single());
    }

    @Test
    void startedQuestionStillExpires() throws Exception {
        String packageId = packageFor("started-user"), sessionId = UUID.randomUUID().toString(), questionId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,'started-user',:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id", sessionId).param("package", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order,answer_started_at,answer_expires_at) VALUES(:id,:session,'已开始且超时的题目','OPEN',0,CURRENT_TIMESTAMP - INTERVAL '6' MINUTE,CURRENT_TIMESTAMP - INTERVAL '1' MINUTE)")
            .param("id", questionId).param("session", sessionId).update();

        mockMvc.perform(post("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/expire", sessionId, questionId).with(jwt().jwt(token -> token.subject("started-user"))))
            .andExpect(status().isOk());
        assertEquals("SKIPPED", jdbc.sql("SELECT state FROM ai_mock_interview_questions WHERE id=:id").param("id", questionId).query(String.class).single());
    }

    @Test
    void finishMarksEmptyAnswerAsUnanswered() throws Exception {
        String packageId = packageFor("finish-user"), sessionId = UUID.randomUUID().toString(), questionId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,'finish-user',:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id", sessionId).param("package", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,confirmed_answer_text,state,sort_order) VALUES(:id,:session,'请介绍事件循环。','','ANSWERED',0)")
            .param("id", questionId).param("session", sessionId).update();

        mockMvc.perform(post("/api/v1/ai-mock-interviews/{id}/finish", sessionId).with(jwt().jwt(token -> token.subject("finish-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINISHED"));
        String finalId = jdbc.sql("SELECT final_interview_id FROM ai_mock_interviews WHERE id=:id").param("id", sessionId).query(String.class).single();
        assertEquals("UNANSWERED", jdbc.sql("SELECT self_assessment FROM interview_questions WHERE interview_id=:id").param("id", finalId).query(String.class).single());
    }

    @Test
    void audioUploadAndDeleteRequireOwnership() throws Exception {
        String packageId = packageFor("audio-user"), sessionId = UUID.randomUUID().toString(), questionId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,'audio-user',:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)").param("id", sessionId).param("package", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'请说明事件循环。','OPEN',0)").param("id", questionId).param("session", sessionId).update();
        MockMultipartFile wav = new MockMultipartFile("file", "answer.wav", "audio/wav", "RIFFxxxxWAVEdata".getBytes());

        mockMvc.perform(multipart("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/audio", sessionId, questionId).file(wav).with(jwt().jwt(token -> token.subject("other-user"))))
            .andExpect(status().isNotFound());
        verify(storage, never()).upload(anyString(), anyString(), org.mockito.ArgumentMatchers.any(byte[].class));

        org.mockito.Mockito.doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("OPEN", jdbc.sql("SELECT state FROM ai_mock_interview_questions WHERE id=:id").param("id",questionId).query(String.class).single());
            return null;
        }).when(storage).upload(anyString(), anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
        mockMvc.perform(multipart("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/audio", sessionId, questionId).file(wav).with(jwt().jwt(token -> token.subject("audio-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(multipart("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/audio", sessionId, questionId).file(wav).with(jwt().jwt(token -> token.subject("audio-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion").value(org.hamcrest.Matchers.nullValue()));
        verify(storage).upload(anyString(), anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
        worker.run();
        mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("audio-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion").value(org.hamcrest.Matchers.nullValue()));
        String assetId = jdbc.sql("SELECT id FROM ai_mock_audio_assets WHERE user_id='audio-user'").query(String.class).single();
        mockMvc.perform(delete("/api/v1/ai-mock-audio-assets/{id}", assetId).with(jwt().jwt(token -> token.subject("other-user"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/ai-mock-audio-assets/{id}", assetId).with(jwt().jwt(token -> token.subject("audio-user"))))
            .andExpect(status().isNoContent());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE id=:id").param("id", assetId).query(Integer.class).single());
        verify(storage).delete(org.mockito.ArgumentMatchers.contains(assetId));
    }

    @Test
    void directAudioUploadReleasesTransactionAndRetriesAfterStorageFailure() throws Exception {
        String user="direct-audio-transaction-user", packageId=packageFor(user), sessionId=UUID.randomUUID().toString(), questionId=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,:user,:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id",sessionId).param("user",user).param("package",packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'请说明事件循环。','OPEN',0)")
            .param("id",questionId).param("session",sessionId).update();
        MockMultipartFile wav=new MockMultipartFile("file","answer.wav","audio/wav","RIFFxxxxWAVEdata".getBytes());
        org.mockito.Mockito.doThrow(new IllegalStateException("Storage unavailable")).when(storage).upload(anyString(),anyString(),org.mockito.ArgumentMatchers.any(byte[].class));
        assertThrows(IllegalStateException.class,() -> voice.audio(user,sessionId,questionId,wav));
        assertEquals("OPEN",jdbc.sql("SELECT state FROM ai_mock_interview_questions WHERE id=:id").param("id",questionId).query(String.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE question_id=:id").param("id",questionId).query(Integer.class).single());

        org.mockito.Mockito.reset(storage);
        org.mockito.Mockito.doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("OPEN",jdbc.sql("SELECT state FROM ai_mock_interview_questions WHERE id=:id").param("id",questionId).query(String.class).single());
            return null;
        }).when(storage).upload(anyString(),anyString(),org.mockito.ArgumentMatchers.any(byte[].class));
        assertNull(voice.audio(user,sessionId,questionId,wav).currentQuestion());
        assertNull(voice.audio(user,sessionId,questionId,wav).currentQuestion());
        assertEquals("ANSWERED",jdbc.sql("SELECT state FROM ai_mock_interview_questions WHERE id=:id").param("id",questionId).query(String.class).single());
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.times(1)).upload(anyString(),anyString(),org.mockito.ArgumentMatchers.any(byte[].class));
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE question_id=:id").param("id",questionId).query(Integer.class).single());
    }

    @Test
    void largeDirectAudioTranscribesFromStoredUrl() throws Exception {
        String user="parallel-transcription-user", packageId=packageFor(user), sessionId=UUID.randomUUID().toString(), questionId=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,:user,:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id",sessionId).param("user",user).param("package",packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'请说明事件循环。','OPEN',0)")
            .param("id",questionId).param("session",sessionId).update();
        byte[] ogg=new byte[2*1024*1024]; System.arraycopy("OggS".getBytes(),0,ogg,0,4);
        when(transcription.transcribe(eq(user),org.mockito.ArgumentMatchers.eq(ogg),eq("audio/ogg"),org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn("转写后的回答");
        org.mockito.Mockito.doReturn(ogg).when(storage).download(anyString());
        voice.audio(user,sessionId,questionId,new MockMultipartFile("file","answer.ogg","audio/ogg",ogg));
        String assetId=jdbc.sql("SELECT id FROM ai_mock_audio_assets WHERE question_id=:id").param("id",questionId).query(String.class).single();
        voice.processAudio(user,sessionId,assetId);
        assertEquals("转写后的回答",jdbc.sql("SELECT transcript FROM ai_mock_audio_assets WHERE question_id=:id").param("id",questionId).query(String.class).single());
        verify(storage).download(anyString());
    }

    @Test
    void concurrentDirectAudioUploadsKeepOneAsset() throws Exception {
        String user="concurrent-direct-audio-user", packageId=packageFor(user), sessionId=UUID.randomUUID().toString(), questionId=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,:user,:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id",sessionId).param("user",user).param("package",packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'请说明事件循环。','OPEN',0)")
            .param("id",questionId).param("session",sessionId).update();
        MockMultipartFile wav=new MockMultipartFile("file","answer.wav","audio/wav","RIFFxxxxWAVEdata".getBytes());
        java.util.concurrent.CountDownLatch uploading=new java.util.concurrent.CountDownLatch(2);
        org.mockito.Mockito.doAnswer(call -> {
            uploading.countDown();
            assertTrue(uploading.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storage).upload(anyString(),anyString(),org.mockito.ArgumentMatchers.any(byte[].class));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> voice.audio(user,sessionId,questionId,wav));
            var second=executor.submit(() -> voice.audio(user,sessionId,questionId,wav));
            assertNull(first.get(10,java.util.concurrent.TimeUnit.SECONDS).currentQuestion());
            assertNull(second.get(10,java.util.concurrent.TimeUnit.SECONDS).currentQuestion());
        }
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE question_id=:id").param("id",questionId).query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='AI_AUDIO'").param("id",sessionId).query(Integer.class).single());
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.times(2)).upload(anyString(),anyString(),org.mockito.ArgumentMatchers.any(byte[].class));
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.times(1)).delete(anyString());
    }

    @Test
    void submittedAudioOpensPreparedQuestionBeforeTranscriptionAndRetryFillsPreviousAnswer() throws Exception {
        String user="audio-release-user",packageId=packageFor(user),sessionId=UUID.randomUUID().toString(),firstId=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,:user,:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id",sessionId).param("user",user).param("package",packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'第一题','OPEN',0)")
            .param("id",firstId).param("session",sessionId).update();
        jdbc.sql("INSERT INTO ai_mock_prepared_questions(ai_mock_interview_id,sort_order,question_text,question_type,competency,project_name,technology) VALUES(:session,1,'第二题','FUNDAMENTAL','事件循环','','浏览器')")
            .param("session",sessionId).update();
        byte[] wav="RIFFxxxxWAVEdata".getBytes();
        MockMultipartFile file=new MockMultipartFile("file","answer.wav","audio/wav",wav);
        var submitted=voice.audio(user,sessionId,firstId,file);
        assertEquals(1,submitted.currentQuestion().sortOrder());
        String nextId=submitted.currentQuestion().id();
        assertEquals(nextId,voice.startAnswer(user,sessionId,nextId).questionId());
        assertEquals(nextId,voice.audio(user,sessionId,firstId,file).currentQuestion().id());
        assertEquals("",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",firstId).query(String.class).single());
        String assetId=jdbc.sql("SELECT id FROM ai_mock_audio_assets WHERE question_id=:id").param("id",firstId).query(String.class).single();
        String taskId=jdbc.sql("SELECT id FROM ai_mock_tasks WHERE resource_id=:session AND task_type='AI_AUDIO'").param("session",sessionId).query(String.class).single();
        org.mockito.Mockito.when(storage.download(anyString())).thenThrow(new IllegalStateException("Storage unavailable"));
        String token=UUID.randomUUID().toString();
        jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=1,worker_token=:token,locked_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",taskId).param("token",token).update();
        var task=new com.interviewagent.ai.AiMockTaskService.ClaimedTask(taskId,user,"AI_AUDIO",sessionId,assetId,token,0);
        var failure=assertThrows(IllegalStateException.class,()->tasks.execute(task,()->voice.processAudio(user,sessionId,assetId)));
        tasks.fail(task,failure);
        assertEquals(nextId,voice.get(user,sessionId).currentQuestion().id());
        assertEquals("FAILED",jdbc.sql("SELECT status FROM ai_mock_audio_assets WHERE id=:id").param("id",assetId).query(String.class).single());
        org.mockito.Mockito.doReturn(wav).when(storage).download(anyString());
        when(transcription.transcribe(eq(user),org.mockito.ArgumentMatchers.eq(wav),eq("audio/wav"),org.mockito.ArgumentMatchers.nullable(String.class)))
            .thenThrow(new IllegalStateException("Tencent ASR unavailable")).thenReturn("第一题回答");
        tasks.retry(user,taskId);
        token=UUID.randomUUID().toString();
        jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=1,worker_token=:token,locked_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",taskId).param("token",token).update();
        var retry=new com.interviewagent.ai.AiMockTaskService.ClaimedTask(taskId,user,"AI_AUDIO",sessionId,assetId,token,0);
        var failedRetry=retry;
        var transcriptionFailure=assertThrows(IllegalStateException.class,()->tasks.execute(failedRetry,()->voice.processAudio(user,sessionId,assetId)));
        tasks.fail(failedRetry,transcriptionFailure);
        assertEquals("FAILED",jdbc.sql("SELECT status FROM ai_mock_audio_assets WHERE id=:id").param("id",assetId).query(String.class).single());
        assertEquals("",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",firstId).query(String.class).single());
        tasks.retry(user,taskId);
        token=UUID.randomUUID().toString();
        jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=1,worker_token=:token,locked_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",taskId).param("token",token).update();
        retry=new com.interviewagent.ai.AiMockTaskService.ClaimedTask(taskId,user,"AI_AUDIO",sessionId,assetId,token,0);
        tasks.execute(retry,()->voice.processAudio(user,sessionId,assetId));
        tasks.complete(retry);
        assertEquals("第一题回答",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",firstId).query(String.class).single());
        assertEquals("READY",jdbc.sql("SELECT status FROM ai_mock_audio_assets WHERE id=:id").param("id",assetId).query(String.class).single());
        assertEquals(nextId,voice.get(user,sessionId).currentQuestion().id());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id AND sort_order=1").param("id",sessionId).query(Integer.class).single());
    }

    @Test
    void resumableAudioUploadIsOwnedIdempotentAndCompletesOnce() throws Exception {
        String user="chunk-user", packageId=packageFor(user), sessionId=UUID.randomUUID().toString(), questionId=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,:user,:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)").param("id",sessionId).param("user",user).param("package",packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order,answer_started_at,answer_expires_at) VALUES(:id,:session,'请说明事件循环。','OPEN',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '5' MINUTE)").param("id",questionId).param("session",sessionId).update();
        byte[] wav="RIFFxxxxWAVEdata".getBytes();
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(wav));
        String base="/api/v1/ai-mock-interviews/"+sessionId+"/questions/"+questionId+"/audio-uploads";
        mockMvc.perform(post(base).contentType("application/json").content("{\"totalBytes\":"+wav.length+",\"sha256\":\""+sha+"\"}").with(jwt().jwt(t->t.subject("other-user")))).andExpect(status().isNotFound());
        String uploadId=json.readTree(mockMvc.perform(post(base).contentType("application/json").content("{\"totalBytes\":"+wav.length+",\"sha256\":\""+sha+"\"}").with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        String part=base+"/"+uploadId+"/parts/0";
        for(int attempt=0;attempt<2;attempt++) mockMvc.perform(put(part).content(wav).header("Content-Range","bytes 0-"+(wav.length-1)+"/"+wav.length).header("X-Chunk-SHA256",sha).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isNoContent());
        verify(storage,org.mockito.Mockito.times(1)).upload(org.mockito.ArgumentMatchers.contains("ai-mock-staging/"),eq("application/octet-stream"),org.mockito.ArgumentMatchers.eq(wav));
        when(storage.download(org.mockito.ArgumentMatchers.contains("ai-mock-staging/"))).thenReturn(wav);
        String complete=base+"/"+uploadId+"/complete";
        mockMvc.perform(post(complete).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk()).andExpect(jsonPath("$.task.taskType").value("AI_FINALIZE_AUDIO"));
        verify(storage,never()).download(org.mockito.ArgumentMatchers.contains("ai-mock-staging/"));
        mockMvc.perform(post(complete).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk());
        worker.run();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE question_id=:question").param("question",questionId).query(Integer.class).single()==0 && System.nanoTime()<deadline) Thread.sleep(10);
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE question_id=:question").param("question",questionId).query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_upload_parts WHERE upload_id=:id").param("id",uploadId).query(Integer.class).single());
    }

    @Test
    void multiPartFinalizeChecksEveryPartAndKeepsFailedUploadForRetry() throws Exception {
        String user="parallel-user", sessionId=UUID.randomUUID().toString(), questionId=UUID.randomUUID().toString(), uploadId=UUID.randomUUID().toString();
        String packageId=packageFor(user);
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,:user,:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("id",sessionId).param("user",user).param("package",packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'请说明事件循环。','TRANSCRIBING',0)")
            .param("id",questionId).param("session",sessionId).update();
        jdbc.sql("INSERT INTO ai_mock_prepared_questions(ai_mock_interview_id,sort_order,question_text,question_type,competency,project_name,technology) VALUES(:session,1,'第二题','FUNDAMENTAL','事件循环','','浏览器')")
            .param("session",sessionId).update();
        byte[] audio=new byte[2*1024*1024+128];
        System.arraycopy("OggS".getBytes(),0,audio,0,4);
        java.util.Arrays.fill(audio,1024*1024,audio.length,(byte)7);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(audio));
        jdbc.sql("INSERT INTO ai_mock_audio_uploads(id,user_id,ai_mock_interview_id,question_id,content_type,total_bytes,total_parts,sha256,status,expires_at) VALUES(:id,:user,:session,:question,'audio/ogg',:bytes,3,:sha,'COMPLETED',CURRENT_TIMESTAMP + INTERVAL '10' MINUTE)")
            .param("id",uploadId).param("user",user).param("session",sessionId).param("question",questionId).param("bytes",audio.length).param("sha",hash).update();
        byte[][] chunks=new byte[3][];
        for(int partNo=0;partNo<3;partNo++) {
            chunks[partNo]=java.util.Arrays.copyOfRange(audio,partNo*1024*1024,Math.min(audio.length,(partNo+1)*1024*1024));
            String chunkHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(chunks[partNo]));
            jdbc.sql("INSERT INTO ai_mock_audio_upload_parts(upload_id,part_no,size_bytes,sha256,object_path) VALUES(:upload,:part,:size,:sha,:path)")
                .param("upload",uploadId).param("part",partNo).param("size",chunks[partNo].length).param("sha",chunkHash).param("path","ai-mock-staging/"+uploadId+"/"+partNo).update();
        }
        String taskId=UUID.randomUUID().toString(), token=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_tasks(id,user_id,task_type,resource_id,related_id,status,attempts,max_attempts,available_at,worker_token,locked_at) VALUES(:id,:user,'AI_FINALIZE_AUDIO',:session,:upload,'PROCESSING',1,3,CURRENT_TIMESTAMP,:token,CURRENT_TIMESTAMP)")
            .param("id",taskId).param("user",user).param("session",sessionId).param("upload",uploadId).param("token",token).update();
        var task=new com.interviewagent.ai.AiMockTaskService.ClaimedTask(taskId,user,"AI_FINALIZE_AUDIO",sessionId,uploadId,token,0);
        org.mockito.Mockito.when(storage.download(anyString())).thenAnswer(call -> {
            int partNo=Integer.parseInt(((String)call.getArgument(0)).substring(((String)call.getArgument(0)).lastIndexOf('/')+1));
            return partNo==1?new byte[chunks[1].length]:chunks[partNo];
        });
        var failure=assertThrows(IllegalArgumentException.class,() -> tasks.execute(task,() -> voice.processAudioUpload(user,sessionId,uploadId)));
        tasks.fail(task,failure);
        assertEquals(3,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_upload_parts WHERE upload_id=:id").param("id",uploadId).query(Integer.class).single());

        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(3);
        java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger(), peak=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            int count=active.incrementAndGet(); peak.accumulateAndGet(count,Math::max);
            try {
                started.countDown();
                assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
                int partNo=Integer.parseInt(((String)call.getArgument(0)).substring(((String)call.getArgument(0)).lastIndexOf('/')+1));
                return chunks[partNo];
            } finally { active.decrementAndGet(); }
        }).when(storage).download(anyString());
        tasks.retry(user,task.id());
        String retryToken=UUID.randomUUID().toString();
        jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=attempts+1,worker_token=:token,locked_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("token",retryToken).param("id",taskId).update();
        var retry=new com.interviewagent.ai.AiMockTaskService.ClaimedTask(taskId,user,"AI_FINALIZE_AUDIO",sessionId,uploadId,retryToken,0);
        tasks.execute(retry,() -> voice.processAudioUpload(user,sessionId,uploadId));
        tasks.complete(retry);
        assertEquals(3,peak.get());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE question_id=:id").param("id",questionId).query(Integer.class).single());
        String saved=jdbc.sql("SELECT transcript FROM ai_mock_audio_assets WHERE question_id=:id").param("id",questionId).query(String.class).single();
        assertTrue(saved.isBlank());
        assertEquals(1,voice.get(user,sessionId).currentQuestion().sortOrder());
        assertEquals("ANSWERED",jdbc.sql("SELECT state FROM ai_mock_interview_questions WHERE id=:id").param("id",questionId).query(String.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='AI_AUDIO'").param("id",sessionId).query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_upload_parts WHERE upload_id=:id").param("id",uploadId).query(Integer.class).single());
        org.mockito.Mockito.verify(storage).upload(org.mockito.ArgumentMatchers.contains("ai-mock/"),eq("audio/ogg"),org.mockito.ArgumentMatchers.eq(audio));
        tasks.deleteForResource(user,sessionId);
    }

    @Test
    void resumableUploadPreflightAllowsItsIntegrityHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/ai-mock-interviews/session/questions/question/audio-uploads/upload/parts/0")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "PUT")
            .header("Access-Control-Request-Headers", "content-range,x-chunk-sha256"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("content-range"), org.hamcrest.Matchers.containsString("x-chunk-sha256"))));
    }

    @Test
    void preparedFirstQuestionStartsOnlyAfterBegin() throws Exception {
        String user="prepared-first-user", packageId=packageFor(user);
        String sessionId=json.readTree(mockMvc.perform(post("/api/v1/ai-mock-interviews/prepare")
            .with(jwt().jwt(t->t.subject(user))).contentType("application/json")
            .content("{\"interviewPackageId\":\""+packageId+"\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        assertEquals(-1,jdbc.sql("SELECT current_question_index FROM ai_mock_interviews WHERE id=:id").param("id",sessionId).query(Integer.class).single());
        mockMvc.perform(post("/api/v1/ai-mock-interviews/{id}/begin",sessionId).with(jwt().jwt(t->t.subject("other-user"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/ai-mock-interviews/{id}/begin",sessionId).with(jwt().jwt(t->t.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
        assertEquals(0,jdbc.sql("SELECT current_question_index FROM ai_mock_interviews WHERE id=:id").param("id",sessionId).query(Integer.class).single());
        jdbc.sql("UPDATE ai_mock_interviews SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' MINUTE WHERE id=:id").param("id",sessionId).update();
        mockMvc.perform(post("/api/v1/ai-mock-interviews/{id}/begin",sessionId).with(jwt().jwt(t->t.subject(user))))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void preparedNextQuestionIsHiddenUntilAnswerAndReleasedOnlyOnce() throws Exception {
        String user="prepared-next-user",packageId=packageFor(user),sessionId=UUID.randomUUID().toString(),firstId=UUID.randomUUID().toString();
        String plan=agent.serialize(agent.parsePlan(json.readTree(validPlan()),false));
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at,question_plan,material_snapshot,generation_version) VALUES(:id,:user,:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE,:plan,:snapshot,'SIMULATION_AGENT_V1')")
            .param("id",sessionId).param("user",user).param("package",packageId).param("plan",plan).param("snapshot",materials.capture(user,packageId).toString()).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,question_type,competency,project_name,technology,state,sort_order) VALUES(:id,:session,'浏览器如何调度微任务？','FUNDAMENTAL','浏览器事件循环','','浏览器','OPEN',0)")
            .param("id",firstId).param("session",sessionId).update();
        when(model.simulate(eq("VOICE_QUESTION"),anyMap())).thenReturn(json.readTree("{\"questionText\":\"浏览器渲染的关键路径是什么？\",\"type\":\"FUNDAMENTAL\",\"competency\":\"渲染流程\",\"projectName\":\"\",\"technology\":\"渲染引擎\"}"));
        tasks.enqueue(user,"AI_PREPARE_NEXT",sessionId,firstId);
        String taskId=jdbc.sql("SELECT id FROM ai_mock_tasks WHERE resource_id=:session AND task_type='AI_PREPARE_NEXT'").param("session",sessionId).query(String.class).single();
        String token=UUID.randomUUID().toString();
        jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=1,worker_token=:token,locked_at=CURRENT_TIMESTAMP WHERE id=:id").param("token",token).param("id",taskId).update();
        var task=new com.interviewagent.ai.AiMockTaskService.ClaimedTask(taskId,user,"AI_PREPARE_NEXT",sessionId,firstId,token,0);
        tasks.execute(task,()->voice.processPrepareNext(user,sessionId,firstId));
        tasks.complete(task);
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_prepared_questions WHERE ai_mock_interview_id=:id").param("id",sessionId).query(Integer.class).single());
        assertEquals(firstId,voice.get(user,sessionId).currentQuestion().id());
        mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/next-preview",sessionId,firstId).with(jwt().jwt(t->t.subject("other-user"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/next-preview",sessionId,firstId).with(jwt().jwt(t->t.subject(user))))
            .andExpect(status().isNotFound());
        jdbc.sql("UPDATE ai_mock_interview_questions SET answer_started_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",firstId).update();
        mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/next-preview",sessionId,firstId).with(jwt().jwt(t->t.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sortOrder").value(1));
        voice.skipAnswer(user,sessionId,firstId);
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_prepared_questions WHERE ai_mock_interview_id=:id AND sort_order=1").param("id",sessionId).query(Integer.class).single());
        assertEquals(1,voice.get(user,sessionId).currentQuestion().sortOrder());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id AND sort_order=1").param("id",sessionId).query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='AI_NEXT'").param("id",sessionId).query(Integer.class).single());
    }

    @Test
    void knowledgeVoiceKeepsQuestionSourceThroughPreviewAndFeedback() throws Exception {
        String user="knowledge-voice-user", packageId=packageFor(user);
        String category=UUID.randomUUID().toString(), document=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO knowledge_categories(id,user_id,name) VALUES(:id,:user,'前端基础')").param("id",category).param("user",user).update();
        jdbc.sql("INSERT INTO knowledge_documents(id,user_id,category_id,original_filename,format,size_bytes,object_path,segment_count) VALUES(:id,:user,:category,'前端题库.md','md',100,'test/front.md',2)")
            .param("id",document).param("user",user).param("category",category).update();
        jdbc.sql("INSERT INTO knowledge_segments(id,document_id,position,kind,location,content) VALUES(:id,:document,0,'paragraph','事件循环','前端开发 浏览器事件循环和微任务调度')")
            .param("id",UUID.randomUUID().toString()).param("document",document).update();
        jdbc.sql("INSERT INTO knowledge_segments(id,document_id,position,kind,location,content) VALUES(:id,:document,1,'paragraph','渲染流程','浏览器渲染引擎的关键路径')")
            .param("id",UUID.randomUUID().toString()).param("document",document).update();
        String unselectedCategory=UUID.randomUUID().toString(), unselectedDocument=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO knowledge_categories(id,user_id,name) VALUES(:id,:user,'未选择的类别')").param("id",unselectedCategory).param("user",user).update();
        jdbc.sql("INSERT INTO knowledge_documents(id,user_id,category_id,original_filename,format,size_bytes,object_path,segment_count) VALUES(:id,:user,:category,'未选择.md','md',10,'test/unselected.md',1)")
            .param("id",unselectedDocument).param("user",user).param("category",unselectedCategory).update();
        jdbc.sql("INSERT INTO knowledge_segments(id,document_id,position,kind,location,content) VALUES(:id,:document,0,'paragraph','其他','前端开发 浏览器事件循环')")
            .param("id",UUID.randomUUID().toString()).param("document",unselectedDocument).update();
        String foreignCategory=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO knowledge_categories(id,user_id,name) VALUES(:id,'another-user','私有资料')").param("id",foreignCategory).update();
        mockMvc.perform(post("/api/v1/ai-mock-interviews/prepare").with(jwt().jwt(t->t.subject(user))).contentType("application/json")
            .content(json.writeValueAsString(new AiMockInterviewApi.StartRequest(packageId,"KNOWLEDGE",List.of(foreignCategory)))))
            .andExpect(status().isNotFound());

        when(model.simulate(eq("VOICE_PLAN"),anyMap())).thenReturn(json.readTree(validPlan()));
        String session=json.readTree(mockMvc.perform(post("/api/v1/ai-mock-interviews/prepare").with(jwt().jwt(t->t.subject(user))).contentType("application/json")
            .content(json.writeValueAsString(new AiMockInterviewApi.StartRequest(packageId,"KNOWLEDGE",List.of(category)))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sourceMode").value("KNOWLEDGE"))
            .andReturn().getResponse().getContentAsString()).path("id").asText();
        assertEquals(document,jdbc.sql("SELECT knowledge_document_ids FROM ai_mock_interviews WHERE id=:id").param("id",session).query(String.class).single());
        runVoiceTask(user,session,"AI_PLAN",null);
        verify(model).simulate(eq("VOICE_PLAN"),org.mockito.ArgumentMatchers.argThat(input->input.get("knowledge").toString().contains("前端题库.md") && !input.get("knowledge").toString().contains("未选择.md")));
        voice.begin(user,session);
        String firstId=voice.get(user,session).currentQuestion().id();
        assertEquals("前端题库.md",voice.get(user,session).currentQuestion().sourceTitle());
        String firstSource=jdbc.sql("SELECT knowledge_source FROM ai_mock_interview_questions WHERE id=:id").param("id",firstId).query(String.class).single();
        assertTrue(firstSource.contains("事件循环"));

        when(model.simulate(eq("VOICE_QUESTION"),anyMap())).thenReturn(json.readTree("{\"questionText\":\"浏览器渲染的关键路径是什么？\",\"type\":\"FUNDAMENTAL\",\"competency\":\"渲染流程\",\"projectName\":\"\",\"technology\":\"渲染引擎\"}"));
        runVoiceTask(user,session,"AI_PREPARE_NEXT",firstId);
        verify(model).simulate(eq("VOICE_QUESTION"),org.mockito.ArgumentMatchers.argThat(input->input.get("knowledge").toString().contains("渲染引擎")));
        assertTrue(jdbc.sql("SELECT knowledge_source FROM ai_mock_prepared_questions WHERE ai_mock_interview_id=:id AND sort_order=1").param("id",session).query(String.class).single().contains("渲染流程"));
        voice.startAnswer(user,session,firstId);
        assertEquals("渲染流程",voice.nextPreview(user,session,firstId).orElseThrow().sourceLocation());
        voice.confirm(user,session,firstId,new AiMockInterviewApi.ConfirmRequest(firstId,"微任务在当前任务之后执行。"));
        assertEquals("渲染流程",voice.get(user,session).currentQuestion().sourceLocation());
        when(model.simulate(eq("VOICE_FEEDBACK"),anyMap())).thenReturn(json.readTree("{\"feedback\":\"回答需要说明微任务队列的执行时机。\"}"));
        runVoiceTask(user,session,"AI_FEEDBACK",firstId);
        verify(model).simulate(eq("VOICE_FEEDBACK"),org.mockito.ArgumentMatchers.argThat(input->input.get("knowledge").toString().contains("事件循环") && !input.get("knowledge").toString().contains("渲染引擎")));
        assertEquals("回答需要说明微任务队列的执行时机。",jdbc.sql("SELECT ai_feedback FROM ai_mock_interview_questions WHERE id=:id").param("id",firstId).query(String.class).single());
    }

    private void runVoiceTask(String user,String session,String type,String relatedId) {
        String id=jdbc.sql("SELECT id FROM ai_mock_tasks WHERE resource_id=:session AND task_type=:type ORDER BY created_at DESC LIMIT 1")
            .param("session",session).param("type",type).query(String.class).single();
        String token=UUID.randomUUID().toString();
        jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=1,worker_token=:token,locked_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("token",token).param("id",id).update();
        var task=new com.interviewagent.ai.AiMockTaskService.ClaimedTask(id,user,type,session,relatedId,token,0);
        voice.processTask(task);
        tasks.complete(task);
    }

    private String packageFor(String user) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO interview_packages(id,user_id,company,role,interview_round) VALUES(:id,:user,'测试公司','前端开发','技术一面')").param("id", id).param("user", user).update();
        for(String project:List.of("订单平台","支付平台","库存平台","发布平台")) {
            String card=UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO project_evidence_cards(id,user_id,project_name,project_description_and_responsibilities,project_highlights,technology_stack) VALUES(:id,:user,:name,'负责开发','压测验证','Java')").param("id",card).param("user",user).param("name",project).update();
            jdbc.sql("INSERT INTO interview_package_evidence_cards(interview_package_id,evidence_card_id) VALUES(:pack,:card)").param("pack",id).param("card",card).update();
        }
        return id;
    }

    private void retryNow(String taskId,int attempts,String status) throws InterruptedException {
        jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP WHERE id=:id AND status='PENDING'").param("id",taskId).update();
        awaitTask(taskId,attempts,status);
    }

    private void awaitTask(String taskId,int attempts,String status) throws InterruptedException {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            int actual=jdbc.sql("SELECT attempts FROM ai_mock_tasks WHERE id=:id").param("id",taskId).query(Integer.class).single();
            String current=jdbc.sql("SELECT status FROM ai_mock_tasks WHERE id=:id").param("id",taskId).query(String.class).single();
            if(actual>=attempts && current.equals(status)) return;
            if(current.equals("PENDING") && actual<attempts) worker.run();
            Thread.sleep(20);
        }
        fail("任务未在预期时间进入 " + status + "，任务 ID：" + taskId);
    }

    private static String validPlan() {
        return """
            {"plan":[
              {"order":1,"type":"FUNDAMENTAL","competency":"浏览器事件循环","projectName":"","technology":"浏览器","angle":"执行顺序"},
              {"order":2,"type":"FUNDAMENTAL","competency":"渲染流程","projectName":"","technology":"渲染引擎","angle":"关键路径"},
              {"order":3,"type":"FUNDAMENTAL","competency":"类型系统","projectName":"","technology":"TypeScript","angle":"类型收窄"},
              {"order":4,"type":"FUNDAMENTAL","competency":"组件更新","projectName":"","technology":"React","angle":"更新机制"},
              {"order":5,"type":"FUNDAMENTAL","competency":"工程构建","projectName":"","technology":"Vite","angle":"构建原理"},
              {"order":6,"type":"PROJECT","competency":"性能定位","projectName":"订单平台","technology":"Performance API","angle":"定位方法"},
              {"order":7,"type":"PROJECT","competency":"状态设计","projectName":"支付平台","technology":"Redux","angle":"状态边界"},
              {"order":8,"type":"PROJECT","competency":"质量保障","projectName":"库存平台","technology":"Vitest","angle":"测试策略"},
              {"order":9,"type":"PROJECT","competency":"发布流程","projectName":"发布平台","technology":"CI","angle":"发布控制"},
              {"order":10,"type":"SCENARIO","competency":"线上故障处理","projectName":"","technology":"日志","angle":"故障排查"}
            ],"firstQuestion":{"questionText":"浏览器如何调度微任务？","type":"FUNDAMENTAL","competency":"浏览器事件循环","projectName":"","technology":"浏览器"}}
            """;
    }
}

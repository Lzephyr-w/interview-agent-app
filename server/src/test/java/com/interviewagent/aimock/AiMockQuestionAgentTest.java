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
    @MockBean AgentPythonClient model;
    @MockBean ReviewModelClient reviewModel;
    @MockBean AiAudioStorage storage;

    @org.junit.jupiter.api.AfterEach
    void noReviewModelCalls() { org.mockito.Mockito.verifyNoInteractions(reviewModel); }

    @Test
    void planContainsRequiredDifferentTypes() throws Exception {
        List<PlanItem> plan = agent.parsePlan(json.readTree(validPlan()),false);
        assertEquals(List.of("FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "PROJECT", "PROJECT", "PROJECT", "PROJECT", "SCENARIO"), plan.stream().map(PlanItem::type).toList());
        assertEquals(3, plan.stream().map(PlanItem::type).distinct().count());
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
        worker.run();
        String taskId = json.readTree(created.getResponse().getContentAsString()).get("task").get("id").asText();
        retryNow(taskId); retryNow(taskId);
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
        worker.run();
        String sessionId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String taskId = json.readTree(mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("invalid-question-user"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("task").path("id").asText();
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE id=:id AND question_plan IS NOT NULL").param("id", sessionId).query(Integer.class).single());
        retryNow(taskId);
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

        mockMvc.perform(multipart("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/audio", sessionId, questionId).file(wav).with(jwt().jwt(token -> token.subject("audio-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion.audio.status").value("TRANSCRIBING"));
        mockMvc.perform(multipart("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/audio", sessionId, questionId).file(wav).with(jwt().jwt(token -> token.subject("audio-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion.audio.status").value("TRANSCRIBING"));
        verify(storage).upload(anyString(), anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
        worker.run();
        mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("audio-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion.audio.status").value("FAILED"));
        String assetId = jdbc.sql("SELECT id FROM ai_mock_audio_assets WHERE user_id='audio-user'").query(String.class).single();
        mockMvc.perform(delete("/api/v1/ai-mock-audio-assets/{id}", assetId).with(jwt().jwt(token -> token.subject("other-user"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/ai-mock-audio-assets/{id}", assetId).with(jwt().jwt(token -> token.subject("audio-user"))))
            .andExpect(status().isNoContent());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE id=:id").param("id", assetId).query(Integer.class).single());
        verify(storage).delete(org.mockito.ArgumentMatchers.contains(assetId));
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

    private void retryNow(String taskId) {
        jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP WHERE id=:id AND status='PENDING'").param("id",taskId).update();
        worker.run();
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

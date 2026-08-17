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
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:ai-mock-agent-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class AiMockQuestionAgentTest {
    @Autowired AiMockQuestionAgent agent;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mockMvc;
    @MockBean ReviewModelClient model;
    @MockBean AiAudioStorage storage;

    @Test
    void planContainsRequiredDifferentTypes() throws Exception {
        List<PlanItem> plan = agent.parsePlan(json.readTree(validPlan()));
        assertEquals(List.of("FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "FUNDAMENTAL", "PROJECT", "PROJECT", "PROJECT", "PROJECT", "SCENARIO"), plan.stream().map(PlanItem::type).toList());
        assertEquals(3, plan.stream().map(PlanItem::type).distinct().count());
    }

    @Test
    void planAcceptsEquivalentModelJson() throws Exception {
        var root = json.readTree(validPlan()).get("plan").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get(0)).put("order", "1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get(0)).put("type", "fundamental");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get(0)).put("projectName", "待补充");
        assertEquals("FUNDAMENTAL", agent.parsePlan(root).getFirst().type());
        assertEquals("", agent.parsePlan(root).getFirst().projectName());
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
        when(model.replyJson(anyString())).thenReturn(json.readTree("{\"plan\":[]}"));
        mockMvc.perform(post("/api/v1/ai-mock-interviews").with(jwt().jwt(token -> token.subject("user-a"))).contentType("application/json").content("{\"interviewPackageId\":\"" + packageId + "\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("计划 JSON 非法")));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE user_id='user-a'").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions").query(Integer.class).single());
    }

    @Test
    void invalidQuestionJsonCreatesNeitherSessionNorQuestion() throws Exception {
        String packageId = packageFor("invalid-question-user");
        when(model.replyJson(anyString())).thenReturn(json.readTree(validPlan()), json.readTree("{\"type\":\"FUNDAMENTAL\"}"));
        mockMvc.perform(post("/api/v1/ai-mock-interviews").with(jwt().jwt(token -> token.subject("invalid-question-user"))).contentType("application/json").content("{\"interviewPackageId\":\"" + packageId + "\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少必填字段")));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE user_id='invalid-question-user'").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions").query(Integer.class).single());
    }

    @Test
    void legacySessionWithoutPlanOrMetadataStillReads() throws Exception {
        String packageId = packageFor("legacy-user"), sessionId = UUID.randomUUID().toString(), questionId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,'legacy-user',:package,'旧公司','前端','一面','RUNNING',CURRENT_TIMESTAMP)").param("id", sessionId).param("package", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'浏览器事件循环如何工作？','OPEN',0)").param("id", questionId).param("session", sessionId).update();
        mockMvc.perform(get("/api/v1/ai-mock-interviews/{id}", sessionId).with(jwt().jwt(token -> token.subject("legacy-user"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalQuestions").value(3)).andExpect(jsonPath("$.currentQuestion.questionType").value("FUNDAMENTAL")).andExpect(jsonPath("$.currentQuestion.competency").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void audioUploadAndDeleteRequireOwnership() throws Exception {
        String packageId = packageFor("audio-user"), sessionId = UUID.randomUUID().toString(), questionId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES(:id,'audio-user',:package,'测试公司','前端','一面','RUNNING',CURRENT_TIMESTAMP)").param("id", sessionId).param("package", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,state,sort_order) VALUES(:id,:session,'请说明事件循环。','OPEN',0)").param("id", questionId).param("session", sessionId).update();
        MockMultipartFile wav = new MockMultipartFile("file", "answer.wav", "audio/wav", "RIFFxxxxWAVEdata".getBytes());

        mockMvc.perform(multipart("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/audio", sessionId, questionId).file(wav).with(jwt().jwt(token -> token.subject("other-user"))))
            .andExpect(status().isNotFound());
        verify(storage, never()).upload(anyString(), anyString(), org.mockito.ArgumentMatchers.any(byte[].class));

        mockMvc.perform(multipart("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/audio", sessionId, questionId).file(wav).with(jwt().jwt(token -> token.subject("audio-user"))))
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
        return id;
    }

    private static String validPlan() {
        return """
            {"plan":[
              {"order":1,"type":"FUNDAMENTAL","competency":"浏览器事件循环","projectName":"","technology":"浏览器","angle":"执行顺序"},
              {"order":2,"type":"FUNDAMENTAL","competency":"渲染流程","projectName":"","technology":"渲染引擎","angle":"关键路径"},
              {"order":3,"type":"FUNDAMENTAL","competency":"类型系统","projectName":"","technology":"TypeScript","angle":"类型收窄"},
              {"order":4,"type":"FUNDAMENTAL","competency":"组件更新","projectName":"","technology":"React","angle":"更新机制"},
              {"order":5,"type":"FUNDAMENTAL","competency":"工程构建","projectName":"","technology":"Vite","angle":"构建原理"},
              {"order":6,"type":"PROJECT","competency":"性能定位","projectName":"","technology":"Performance API","angle":"定位方法"},
              {"order":7,"type":"PROJECT","competency":"状态设计","projectName":"","technology":"Redux","angle":"状态边界"},
              {"order":8,"type":"PROJECT","competency":"质量保障","projectName":"","technology":"Vitest","angle":"测试策略"},
              {"order":9,"type":"PROJECT","competency":"发布流程","projectName":"","technology":"CI","angle":"发布控制"},
              {"order":10,"type":"SCENARIO","competency":"线上故障处理","projectName":"","technology":"日志","angle":"故障排查"}
            ]}
            """;
    }
}

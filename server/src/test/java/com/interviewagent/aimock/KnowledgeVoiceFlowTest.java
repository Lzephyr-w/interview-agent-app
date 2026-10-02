package com.interviewagent.aimock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.ai.AiMockTaskService;
import com.interviewagent.ai.AiMockTaskWorker;
import com.interviewagent.ai.SimulationContract;
import com.interviewagent.ai.SimulationException;
import com.interviewagent.ai.storage.AiAudioStorage;
import com.interviewagent.ai.storage.AudioTranscriptionService;
import com.interviewagent.knowledge.KnowledgeService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"SUPABASE_URL=https://example.supabase.co","spring.datasource.url=jdbc:h2:mem:knowledge-voice-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.default-schema=PUBLIC","spring.flyway.schemas=PUBLIC","spring.flyway.create-schemas=false"})
class KnowledgeVoiceFlowTest {
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired AiMockInterviewService voice;
    @Autowired AiMockTaskService tasks;
    @Autowired KnowledgeService knowledge;
    @MockBean AiMockTaskWorker worker;
    @MockBean AgentPythonClient model;
    @MockBean AiAudioStorage storage;
    @MockBean AudioTranscriptionService transcription;
    @Value("${app.agent.url}") String agentUrl;
    @Value("${app.agent.internal-key}") String agentKey;
    final List<String> questions=List.of("微任务队列在当前任务结束后怎样排空？","解释闭包在函数返回后保存变量的原因？","浏览器重绘时哪些样式属性参与计算？","事件捕获阶段的监听器按什么顺序执行？","描述模块循环依赖的初始化边界？","订单索引为何影响查询执行计划？","支付幂等键应该在什么时机写入？","库存预扣失败的事务如何回滚？","发布灰度期间怎样验证兼容性？","线上缓存击穿应怎样定位根因？");

    // Explicit opt-in: real configured model, synthetic materials and isolated H2 only.
    @Test @EnabledIfEnvironmentVariable(named="KNOWLEDGE_LIVE_SMOKE",matches="1")
    void realModelCompletesTenQuestionsAndVariesConsecutiveSessions() throws Exception {
        Fixture f=fixture();
        List<String> topics=List.of("闭包通过词法环境保留外层变量，需注意引用持有与内存释放。","浏览器事件循环在任务结束后排空微任务，递归微任务可能阻塞渲染。","CSS布局、重绘与合成成本不同，应通过性能面板验证优化。","TypeScript泛型表达输入输出约束，类型收窄依赖可判别条件。","HTTP缓存通过Cache-Control与ETag协商，需验证资源失效策略。");
        for(int i=0;i<topics.size();i++) jdbc.sql("UPDATE knowledge_segments SET content=:text WHERE document_id=:id AND position=:position").param("text","前端开发 JavaScript "+topics.get(i)).param("id",f.document).param("position",i).update();
        var real=new AgentPythonClient(json,agentUrl,agentKey);
        when(model.simulate(anyString(),anyMap())).thenAnswer(call->real.simulate(call.getArgument(0),call.getArgument(1)));
        var session=voice.prepare(f.user,request(f,f.category)); runReal(f.user,session.id(),"AI_FIRST");
        Set<String> firstSources=new HashSet<>(); firstSources.add(voice.get(f.user,session.id()).currentQuestion().sourceLocation());
        voice.begin(f.user,session.id());
        for(int order=0;order<10;order++) {
            var current=voice.get(f.user,session.id()).currentQuestion(); assertEquals(order,current.sortOrder());
            if(order<9) runReal(f.user,session.id(),"AI_PREPARE_NEXT");
            voice.confirm(f.user,session.id(),current.id(),new AiMockInterviewApi.ConfirmRequest(current.id(),"联调样本回答：根据实际实现分析边界，并通过测试验证。"));
        }
        for(int i=0;i<10;i++) runReal(f.user,session.id(),"AI_FEEDBACK");
        voice.finish(f.user,session.id()); assertEquals("FINISHED",voice.get(f.user,session.id()).status());
        for(int i=0;i<2;i++) {
            var next=voice.prepare(f.user,request(f,f.category)); runReal(f.user,next.id(),"AI_FIRST");
            assertTrue(firstSources.add(voice.get(f.user,next.id()).currentQuestion().sourceLocation()));
        }
        verify(model,never()).simulate(eq("VOICE_PLAN"),anyMap());
        verify(model,never()).simulate(eq("VOICE_PLAN_ONLY"),anyMap());
    }
    private void runReal(String user,String session,String type) {
        for(int attempt=0;attempt<3;attempt++) {
            var task=claim(user,session,type);
            try { voice.processTask(task); tasks.complete(task); return; }
            catch(RuntimeException error) { tasks.fail(task,error); if(attempt==2) throw error; }
        }
    }

    @Test void roomPreparationAndCompleteTenQuestionFlowKeepSourcesAndFeedback() throws Exception {
        Fixture f=fixture();
        List<Map<String,Object>> calls=new ArrayList<>();
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->{
            Map<String,Object> input=call.getArgument(1); calls.add(input);
            SimulationContract.input("VOICE_KNOWLEDGE_QUESTION",json.valueToTree(input));
            return draft(input);
        });
        var prepared=voice.prepare(f.user,request(f,f.category));
        assertEquals(0,prepared.completedQuestions());
        assertTrue(prepared.prepared()); assertEquals(10,prepared.totalQuestions()); assertEquals("AI_FIRST",prepared.task().taskType());
        verifyNoInteractions(model);
        run(f.user,prepared.id(),"AI_FIRST");
        var ready=voice.get(f.user,prepared.id()); assertTrue(ready.prepared()); assertNotNull(ready.currentQuestion());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE id=:id AND question_plan IS NOT NULL").param("id",prepared.id()).query(Integer.class).single());
        voice.begin(f.user,prepared.id()); voice.begin(f.user,prepared.id());
        assertFalse(voice.get(f.user,prepared.id()).prepared());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='AI_FIRST'").param("id",prepared.id()).query(Integer.class).single());
        String firstId=ready.currentQuestion().id();
        String previousLocation="";
        for(int order=0;order<10;order++) {
            assertEquals(order,voice.get(f.user,prepared.id()).completedQuestions());
            var current=voice.get(f.user,prepared.id()).currentQuestion();
            assertEquals(order,current.sortOrder());
            assertNotEquals(previousLocation,current.sourceLocation()); previousLocation=current.sourceLocation();
            assertEquals(order<5?"FUNDAMENTAL":order<9?"PROJECT":((JsonNode)calls.getLast().get("target")).path("type").asText(),current.questionType());
            if(order<9) {
                run(f.user,prepared.id(),"AI_PREPARE_NEXT");
                voice.startAnswer(f.user,prepared.id(),current.id());
                var preview=voice.nextPreview(f.user,prepared.id(),current.id()).orElseThrow();
                assertEquals(order+1,preview.sortOrder());
                voice.confirm(f.user,prepared.id(),current.id(),new AiMockInterviewApi.ConfirmRequest(current.id(),"这是本人的确认回答。"));
                assertEquals(preview.sourceLocation(),voice.get(f.user,prepared.id()).currentQuestion().sourceLocation());
            } else voice.confirm(f.user,prepared.id(),current.id(),new AiMockInterviewApi.ConfirmRequest(current.id(),"这是本人的确认回答。"));
        }
        assertEquals(10,calls.size());
        assertEquals(10,voice.get(f.user,prepared.id()).completedQuestions());
        String firstSource=jdbc.sql("SELECT knowledge_source FROM ai_mock_interview_questions WHERE id=:id").param("id",firstId).query(String.class).single();
        assertEquals(5,jdbc.sql("SELECT COUNT(DISTINCT knowledge_source) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id AND sort_order<5").param("id",prepared.id()).query(Integer.class).single());
        jdbc.sql("DELETE FROM knowledge_documents WHERE id=:id").param("id",f.document).update();
        when(model.simulate(eq("VOICE_FEEDBACK"),anyMap())).thenReturn(json.readTree("{\"feedback\":\"需要说明相关执行条件。\"}"));
        run(f.user,prepared.id(),"AI_FEEDBACK");
        String firstText=json.readTree(firstSource).path("text").asText();
        verify(model).simulate(eq("VOICE_FEEDBACK"),argThat(input->input.get("knowledge").toString().contains(firstText)));
        voice.finish(f.user,prepared.id());
        assertEquals("FINISHED",voice.get(f.user,prepared.id()).status());
        verify(model,never()).simulate(eq("VOICE_PLAN"),anyMap());
        verify(model,never()).simulate(eq("VOICE_PLAN_ONLY"),anyMap());
    }

    @Test void completionCountSurvivesTheGapBeforeNextQuestionAndBackgroundAudio() throws Exception {
        Fixture f=fixture();
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->draft(call.getArgument(1)));
        var session=voice.create(f.user,request(f,f.category));
        assertEquals(0,session.completedQuestions()); assertNull(session.currentQuestion());
        run(f.user,session.id(),"AI_FIRST");
        var first=voice.get(f.user,session.id()).currentQuestion();
        voice.confirm(f.user,session.id(),first.id(),new AiMockInterviewApi.ConfirmRequest(first.id(),"已确认的回答。"));
        tasks.enqueue(f.user,"AI_AUDIO",session.id(),UUID.randomUUID().toString());
        var waiting=voice.get(f.user,session.id());
        assertNull(waiting.currentQuestion()); assertEquals("AI_AUDIO",waiting.task().taskType());
        assertEquals(1,waiting.completedQuestions()); assertTrue(waiting.completedQuestions()<waiting.totalQuestions());
        run(f.user,session.id(),"AI_NEXT");
        assertEquals(1,voice.get(f.user,session.id()).completedQuestions());
        jdbc.sql("UPDATE ai_mock_interview_questions SET state='SKIPPED' WHERE ai_mock_interview_id=:id AND sort_order=1").param("id",session.id()).update();
        assertEquals(2,voice.get(f.user,session.id()).completedQuestions());
    }

    @Test void consecutiveSessionsRotateSourcesAndAnglesWithBoundedHistory() throws Exception {
        Fixture f=fixture(); List<Map<String,Object>> calls=new ArrayList<>();
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->{Map<String,Object> input=call.getArgument(1); calls.add(input); var result=(com.fasterxml.jackson.databind.node.ObjectNode)draft(input); String knowledge=input.get("knowledge").toString(); int topic=Character.digit(knowledge.charAt(knowledge.indexOf("主题")+2),10); return result.put("competency","子能力"+topic).put("questionText",questions.get(topic));});
        Set<String> sources=new HashSet<>();
        for(int i=0;i<4;i++) {
            var s=voice.prepare(f.user,request(f,f.category)); run(f.user,s.id(),"AI_FIRST");
            assertTrue(sources.add(voice.get(f.user,s.id()).currentQuestion().sourceLocation()));
        }
        assertTrue(((JsonNode)calls.get(1).get("recentQuestions")).size()>0);
        assertTrue(((JsonNode)calls.getLast().get("recentQuestions")).size()<=3);
        var direct=voice.create(f.user,request(f,f.category));
        assertFalse(direct.prepared()); assertEquals("AI_FIRST",direct.task().taskType());
        voice.begin(f.user,direct.id());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='AI_FIRST'").param("id",direct.id()).query(Integer.class).single());
    }

    @Test void concurrentSessionsReserveDifferentFirstSources() throws Exception {
        Fixture f=fixture();
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->draft(call.getArgument(1)));
        AiMockInterviewApi.Session first=null, second=null, previous=voice.prepare(f.user,request(f,f.category));
        for(int attempt=0;attempt<50;attempt++) {
            var next=voice.prepare(f.user,request(f,f.category));
            UUID a=UUID.fromString(previous.id()), b=UUID.fromString(next.id());
            if(new Random(a.getMostSignificantBits()^a.getLeastSignificantBits()).nextInt(5)
                ==new Random(b.getMostSignificantBits()^b.getLeastSignificantBits()).nextInt(5)) {
                first=previous; second=next; break;
            }
            previous=next;
        }
        assertNotNull(first); assertNotNull(second);
        var firstSession=first; var secondSession=second;
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->{gate.await(); run(f.user,firstSession.id(),"AI_FIRST"); return voice.get(f.user,firstSession.id()).currentQuestion().sourceLocation();});
            var b=workers.submit(()->{gate.await(); run(f.user,secondSession.id(),"AI_FIRST"); return voice.get(f.user,secondSession.id()).currentQuestion().sourceLocation();});
            gate.countDown();
            assertNotEquals(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void failedQuestionKeepsFrozenContextEvenAfterSourceChanges() throws Exception {
        Fixture f=fixture(); List<Map<String,Object>> calls=new ArrayList<>();
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->{Map<String,Object> input=call.getArgument(1); calls.add(input); var result=draft(input); if(calls.size()==1) ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("type","PROJECT"); return result;});
        var s=voice.prepare(f.user,request(f,f.category)); var first=claim(f.user,s.id(),"AI_FIRST");
        var failure=assertThrows(SimulationException.class,()->voice.processTask(first)); tasks.fail(first,failure);
        jdbc.sql("UPDATE knowledge_segments SET content='已修改的资料' WHERE document_id=:id").param("id",f.document).update();
        run(f.user,s.id(),"AI_FIRST");
        assertEquals(calls.get(0).get("target"),calls.get(1).get("target"));
        assertEquals(calls.get(0).get("knowledge"),calls.get(1).get("knowledge"));
        assertEquals(calls.get(0).get("recentQuestions"),calls.get(1).get("recentQuestions"));
        assertNotNull(voice.get(f.user,s.id()).currentQuestion());
    }

    @Test void recentDuplicatesRetryAndSmallCorporaStillVaryAngles() throws Exception {
        Fixture f=fixture();
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->draft(call.getArgument(1)));
        var first=voice.prepare(f.user,request(f,f.category)); run(f.user,first.id(),"AI_FIRST");
        var second=voice.prepare(f.user,request(f,f.category)); var task=claim(f.user,second.id(),"AI_FIRST");
        var failure=assertThrows(SimulationException.class,()->voice.processTask(task)); tasks.fail(task,failure);
        assertNull(voice.get(f.user,second.id()).currentQuestion());
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->((com.fasterxml.jackson.databind.node.ObjectNode)draft(call.getArgument(1))).put("questionText",questions.get(1)).put("competency","词法作用域"));
        run(f.user,second.id(),"AI_FIRST");
        assertNotEquals(voice.get(f.user,first.id()).currentQuestion().sourceLocation(),voice.get(f.user,second.id()).currentQuestion().sourceLocation());

        Fixture small=fixture();
        jdbc.sql("DELETE FROM knowledge_segments WHERE document_id=:id AND position>0").param("id",small.document).update();
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->draft(call.getArgument(1)));
        String previousAngle="";
        for(int i=0;i<3;i++) {
            var session=voice.prepare(small.user,request(small,small.category)); run(small.user,session.id(),"AI_FIRST");
            var context=json.readTree(jdbc.sql("SELECT knowledge_contexts FROM ai_mock_interviews WHERE id=:id").param("id",session.id()).query(String.class).single()).path("0");
            assertFalse(context.path("avoidRecent").asBoolean());
            assertNotEquals(previousAngle,context.path("target").path("angle").asText());
            previousAngle=context.path("target").path("angle").asText();
        }
    }

    @Test void changedCategoriesAndCancelledTasksCannotPublishOldQuestions() throws Exception {
        Fixture f=fixture();
        assertThrows(NoSuchElementException.class,()->voice.prepare("another-user",request(f,f.category)));
        var old=voice.prepare(f.user,request(f,f.category)); var claimed=claim(f.user,old.id(),"AI_FIRST");
        voice.delete(f.user,old.id());
        assertThrows(RuntimeException.class,()->voice.processTask(claimed)); verifyNoInteractions(model);
        when(model.simulate(eq("VOICE_KNOWLEDGE_QUESTION"),anyMap())).thenAnswer(call->{Map<String,Object> input=call.getArgument(1); assertTrue(input.get("knowledge").toString().contains("B类别")); assertFalse(input.get("knowledge").toString().contains("A类别")); return draft(input);});
        var fresh=voice.prepare(f.user,request(f,f.otherCategory));
        voice.begin(f.user,fresh.id()); voice.begin(f.user,fresh.id());
        assertFalse(voice.get(f.user,fresh.id()).prepared()); assertNull(voice.get(f.user,fresh.id()).currentQuestion());
        run(f.user,fresh.id(),"AI_FIRST");
        assertEquals("B类别.md",voice.get(f.user,fresh.id()).currentQuestion().sourceTitle());
        voice.delete(f.user,fresh.id());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id").param("id",fresh.id()).query(Integer.class).single());
    }

    @Test void selectedDocumentsValidateAllCategoriesWithOneLookup() {
        Fixture f=fixture();
        assertEquals(2,knowledge.selectedDocuments(f.user,List.of(f.category,f.otherCategory)).size());
        assertThrows(NoSuchElementException.class,()->knowledge.selectedDocuments(f.user,List.of(f.category,UUID.randomUUID().toString())));
        String empty=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO knowledge_categories(id,user_id,name) VALUES(:id,:user,'空类别')").param("id",empty).param("user",f.user).update();
        assertEquals("所选类别中有空类别，请先上传文档。",assertThrows(IllegalArgumentException.class,
            ()->knowledge.selectedDocuments(f.user,List.of(f.category,empty))).getMessage());
    }

    private JsonNode draft(Map<String,Object> input) {
        JsonNode target=(JsonNode)input.get("target"); int order=target.path("order").asInt();
        return json.createObjectNode().put("questionText",questions.get(order-1)).put("type",target.path("type").asText()).put("projectName",target.path("projectName").asText()).put("competency","能力"+order).put("technology","技术"+order);
    }
    private AiMockTaskService.ClaimedTask claim(String user,String session,String type) {
        String id=jdbc.sql("SELECT id FROM ai_mock_tasks WHERE resource_id=:id AND task_type=:type AND status='PENDING' ORDER BY created_at LIMIT 1").param("id",session).param("type",type).query(String.class).single();
        String token=UUID.randomUUID().toString();
        jdbc.sql("UPDATE ai_mock_tasks SET status='PROCESSING',attempts=attempts+1,worker_token=:token,locked_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",id).param("token",token).update();
        String related=jdbc.sql("SELECT related_id FROM ai_mock_tasks WHERE id=:id").param("id",id).query(String.class).single();
        return new AiMockTaskService.ClaimedTask(id,user,type,session,related,token,0);
    }
    private void run(String user,String session,String type) { var task=claim(user,session,type); voice.processTask(task); tasks.complete(task); }
    private AiMockInterviewApi.StartRequest request(Fixture f,String category) { return new AiMockInterviewApi.StartRequest(f.pack,"KNOWLEDGE",List.of(category)); }
    private Fixture fixture() {
        String user=UUID.randomUUID().toString(), pack=UUID.randomUUID().toString(), category=UUID.randomUUID().toString(), other=UUID.randomUUID().toString(), document=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO interview_packages(id,user_id,company,role,interview_round) VALUES(:id,:user,'公司','前端开发','一面')").param("id",pack).param("user",user).update();
        for(String project:List.of("订单平台","支付平台")) {
            String id=UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO project_evidence_cards(id,user_id,project_name,project_description_and_responsibilities,project_highlights,technology_stack) VALUES(:id,:user,:project,'开发职责','实际改进','JavaScript')").param("id",id).param("user",user).param("project",project).update();
            jdbc.sql("INSERT INTO interview_package_evidence_cards(interview_package_id,evidence_card_id) VALUES(:pack,:card)").param("pack",pack).param("card",id).update();
        }
        for(String cat:List.of(category,other)) {
            String title=cat.equals(category)?"A类别":"B类别", doc=cat.equals(category)?document:UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO knowledge_categories(id,user_id,name) VALUES(:id,:user,:title)").param("id",cat).param("user",user).param("title",title).update();
            jdbc.sql("INSERT INTO knowledge_documents(id,user_id,category_id,original_filename,format,size_bytes,object_path,segment_count) VALUES(:id,:user,:cat,:title,'md',100,'test.md',5)").param("id",doc).param("user",user).param("cat",cat).param("title",title+".md").update();
            for(int i=0;i<5;i++) jdbc.sql("INSERT INTO knowledge_segments(id,document_id,position,kind,location,content) VALUES(:id,:doc,:order,'PASSAGE',:location,'前端开发 JavaScript 浏览器机制与项目验证')").param("id",UUID.randomUUID().toString()).param("doc",doc).param("order",i).param("location","主题"+i).update();
        }
        return new Fixture(user,pack,category,other,document);
    }
    private record Fixture(String user,String pack,String category,String otherCategory,String document) {}
}

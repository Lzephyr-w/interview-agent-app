package com.interviewagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.storage.AiAudioStorage;
import com.interviewagent.ai.storage.AudioTranscriptionService;
import com.interviewagent.mock.MockInterviewService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"SUPABASE_URL=https://example.supabase.co","app.ai-mock-task.poll-ms=600000","spring.datasource.url=jdbc:h2:mem:simulation-workflows;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.default-schema=PUBLIC","spring.flyway.schemas=PUBLIC","spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class SimulationWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired AiMockTaskWorker worker;
    @Autowired AiMockTaskService tasks;
    @Autowired MockInterviewService text;
    @MockBean AgentPythonClient agent;
    @MockBean ReviewModelClient review;
    @MockBean AiAudioStorage storage;
    @MockBean AudioTranscriptionService transcription;
    final List<JsonNode> requests=new ArrayList<>();

    @BeforeEach void setup() {
        jdbc.sql("DELETE FROM ai_mock_tasks").update();
        when(agent.simulate(anyString(),anyMap())).thenAnswer(call -> {
            String operation=call.getArgument(0);
            JsonNode input=json.valueToTree(call.getArgument(1));
            requests.add(json.createObjectNode().put("operation",operation).set("input",input));
            if (operation.equals("VOICE_PLAN")) return plan(input.path("materials").path("cards"));
            if (operation.equals("VOICE_QUESTION")) {
                var result=input.path("slot").deepCopy();
                var node=(com.fasterxml.jackson.databind.node.ObjectNode)result;
                int index=node.path("order").asInt(); node.remove(List.of("order","angle"));
                node.put("questionText",index==1?"浏览器如何调度微任务？":"类型系统怎样约束接口边界？"); return node;
            }
            return json.createObjectNode().put(operation.contains("FEEDBACK")?"feedback":"questionText",
                operation.contains("FEEDBACK")?"请补充可验证的测试证据。":operation.equals("TEXT_FOLLOW_UP")?"线上效果如何衡量？":"缓存策略如何取舍？");
        });
    }
    @AfterEach void noReview() { verifyNoInteractions(review); }

    JsonNode plan(JsonNode projects) {
        var result=json.createObjectNode(); var list=result.putArray("plan");
        for(int i=1;i<=10;i++) list.addObject().put("order",i).put("type",i<=5?"FUNDAMENTAL":i<=9?"PROJECT":"SCENARIO")
            .put("competency","能力"+i).put("projectName",i>=6&&i<=9?projects.get(i-6).path("projectName").asText():"").put("technology","技术"+i).put("angle","角度"+i);
        result.putObject("firstQuestion").put("questionText","浏览器如何调度微任务？").put("type","FUNDAMENTAL").put("competency","能力1").put("projectName","").put("technology","技术1");
        return result;
    }
    String pack(String user) {
        String id=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO job_descriptions(id,user_id,company,role,content) VALUES(:id,:user,'公司','开发',:text)").param("id",id).param("user",user).param("text",user+"专属JD").update();
        jdbc.sql("INSERT INTO resume_files(id,user_id,original_filename,content_type,size_bytes,object_path,parsed_status,parsed_text) VALUES(:id,:user,'resume.pdf','application/pdf',1,:id,'READY',:text)").param("id",id).param("user",user).param("text",user+"专属简历").update();
        jdbc.sql("INSERT INTO interview_packages(id,user_id,company,role,interview_round,job_description_id,resume_file_id) VALUES(:id,:user,'公司','开发','一面',:id,:id)").param("id",id).param("user",user).update();
        for(int index=1;index<=4;index++) {
            String card=UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO project_evidence_cards(id,user_id,project_name,project_description_and_responsibilities,project_highlights,technology_stack) VALUES(:id,:user,:name,'负责开发','压测验证','Java')").param("id",card).param("user",user).param("name",user+"项目"+index).update();
            jdbc.sql("INSERT INTO interview_package_evidence_cards(interview_package_id,evidence_card_id) VALUES(:pack,:card)").param("pack",id).param("card",card).update();
        }
        return id;
    }
    JsonNode create(String user,boolean voice,String pack) throws Exception {
        return postJson(user,"/api/v1/"+(voice?"ai-mock-interviews":"mock-interviews"),"{\"interviewPackageId\":\""+pack+"\"}",201);
    }
    JsonNode postJson(String user,String path,String body,int status) throws Exception {
        return json.readTree(mvc.perform(post(path).with(jwt().jwt(t->t.subject(user))).contentType("application/json").content(body)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    JsonNode getSession(String user,String id,boolean voice) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/"+(voice?"ai-mock-interviews/":"mock-interviews/")+id).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void newVoicePlanWritesFirstQuestionWithOneModelCall() throws Exception {
        String user="one-call",id=create(user,true,pack(user)).path("id").asText();
        worker.run();
        verify(agent,times(1)).simulate(eq("VOICE_PLAN"),anyMap());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id").param("id",id).query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='AI_FIRST'").param("id",id).query(Integer.class).single());
    }

    @Test void textAnswerAdvancesBeforeFeedbackAndKeepsFollowupAnswerable() throws Exception {
        String user="text-p0",id=create(user,false,pack(user)).path("id").asText();
        worker.run();
        String first=getSession(user,id,false).path("currentQuestion").path("id").asText();
        clearInvocations(agent);
        doThrow(new SimulationException("MODEL_TIMEOUT")).when(agent).simulate(eq("TEXT_FEEDBACK"),anyMap());
        postJson(user,"/api/v1/mock-interviews/"+id+"/answer","{\"questionId\":\""+first+"\",\"answerText\":\"我负责缓存方案和上线验证。\",\"selfAssessment\":\"GOOD\"}",200);
        JsonNode submitted=getSession(user,id,false);
        assertEquals("MOCK_NEXT",submitted.path("task").path("taskType").asText());
        assertTrue(submitted.path("currentQuestion").isNull());
        assertEquals("我负责缓存方案和上线验证。",jdbc.sql("SELECT answer_text FROM mock_interview_questions WHERE id=:id").param("id",first).query(String.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='MOCK_NEXT'").param("id",id).query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='MOCK_FEEDBACK'").param("id",id).query(Integer.class).single());

        worker.run();
        JsonNode advanced=getSession(user,id,false);
        assertEquals("FOLLOW_UP",advanced.path("currentQuestion").path("questionKind").asText());
        assertEquals("MOCK_FEEDBACK",advanced.path("task").path("taskType").asText());
        assertEquals("PENDING",advanced.path("task").path("status").asText());
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id=:id").param("id",id).query(Integer.class).single());
        assertEquals("",jdbc.sql("SELECT ai_feedback FROM mock_interview_questions WHERE id=:id").param("id",first).query(String.class).single());
        var order=inOrder(agent);
        order.verify(agent).simulate(eq("TEXT_FOLLOW_UP"),anyMap());
        order.verify(agent).simulate(eq("TEXT_FEEDBACK"),anyMap());

        String followup=advanced.path("currentQuestion").path("id").asText();
        JsonNode second=postJson(user,"/api/v1/mock-interviews/"+id+"/answer","{\"questionId\":\""+followup+"\",\"answerText\":\"我补充了线上效果验证。\",\"selfAssessment\":\"GOOD\"}",200);
        assertEquals("MOCK_NEXT",second.path("task").path("taskType").asText());
        assertTrue(second.path("currentQuestion").isNull());
        assertEquals("我补充了线上效果验证。",jdbc.sql("SELECT answer_text FROM mock_interview_questions WHERE id=:id").param("id",followup).query(String.class).single());
    }

    @Test void textNextFailureKeepsAnswerAndDoesNotInsertDuplicateQuestion() throws Exception {
        String user="text-next-failure",id=create(user,false,pack(user)).path("id").asText();
        worker.run();
        String first=getSession(user,id,false).path("currentQuestion").path("id").asText();
        doThrow(new SimulationException("MODEL_TIMEOUT")).when(agent).simulate(eq("TEXT_FOLLOW_UP"),anyMap());
        postJson(user,"/api/v1/mock-interviews/"+id+"/answer","{\"questionId\":\""+first+"\",\"answerText\":\"已保存的回答。\",\"selfAssessment\":\"GOOD\"}",200);
        worker.run();
        var task=tasks.latest(user,id);
        assertEquals("MOCK_NEXT",task.taskType());
        assertEquals("PENDING",task.status());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id=:id").param("id",id).query(Integer.class).single());
        assertEquals("已保存的回答。",jdbc.sql("SELECT answer_text FROM mock_interview_questions WHERE id=:id").param("id",first).query(String.class).single());
        for(int attempt=2;attempt<=3;attempt++) {
            jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",task.id()).update();
            worker.run();
            task=tasks.latest(user,id);
            assertEquals(attempt<3?"PENDING":"FAILED",task.status());
            assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id=:id").param("id",id).query(Integer.class).single());
        }
        JsonNode finished=postJson(user,"/api/v1/mock-interviews/"+id+"/finish","",200);
        String formal=finished.path("formalInterviewId").asText();
        assertEquals("已保存的回答。",jdbc.sql("SELECT answer_text FROM interview_questions WHERE interview_id=:id").param("id",formal).query(String.class).single());
    }

    @Test void legacyMockAnswerRetryBackfillsSplitTasksIdempotently() throws Exception {
        String user="legacy-text-answer",id=create(user,false,pack(user)).path("id").asText();
        worker.run();
        String first=getSession(user,id,false).path("currentQuestion").path("id").asText();
        postJson(user,"/api/v1/mock-interviews/"+id+"/answer","{\"questionId\":\""+first+"\",\"answerText\":\"旧任务回答。\",\"selfAssessment\":\"GOOD\"}",200);
        jdbc.sql("DELETE FROM ai_mock_tasks WHERE resource_id=:id").param("id",id).update();
        String old=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_tasks(id,user_id,task_type,resource_id,related_id,status,max_attempts) VALUES(:id,:user,'MOCK_ANSWER',:resource,:related,'PENDING',3)")
            .param("id",old).param("user",user).param("resource",id).param("related",first).update();
        clearInvocations(agent);
        var claimed=tasks.claim();
        text.processTask(claimed);
        tasks.fail(claimed,new SimulationException("INVALID_MODEL_OUTPUT",false));
        tasks.retry(user,old);
        worker.run();
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id=:id").param("id",id).query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='MOCK_NEXT'").param("id",id).query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id AND task_type='MOCK_FEEDBACK'").param("id",id).query(Integer.class).single());
        assertEquals("旧任务回答。",jdbc.sql("SELECT answer_text FROM mock_interview_questions WHERE id=:id").param("id",first).query(String.class).single());
        assertEquals("请补充可验证的测试证据。",jdbc.sql("SELECT ai_feedback FROM mock_interview_questions WHERE id=:id").param("id",first).query(String.class).single());
        verify(agent,times(1)).simulate(eq("TEXT_FOLLOW_UP"),anyMap());
        verify(agent,times(1)).simulate(eq("TEXT_FEEDBACK"),anyMap());
    }

    @Test void historicalFirstAndCreateTasksStillAdvance() throws Exception {
        String firstUser="legacy-first",firstId=create(firstUser,true,pack(firstUser)).path("id").asText();
        String firstTask=jdbc.sql("SELECT id FROM ai_mock_tasks WHERE resource_id=:id").param("id",firstId).query(String.class).single();
        String projectJson="[{\"projectName\":\""+firstUser+"项目1\"},{\"projectName\":\""+firstUser+"项目2\"},{\"projectName\":\""+firstUser+"项目3\"},{\"projectName\":\""+firstUser+"项目4\"}]";
        jdbc.sql("UPDATE ai_mock_interviews SET question_plan=:plan WHERE id=:id").param("id",firstId).param("plan",plan(json.readTree(projectJson)).toString()).update();
        jdbc.sql("UPDATE ai_mock_tasks SET task_type='AI_FIRST' WHERE id=:id").param("id",firstTask).update();
        worker.run();
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id").param("id",firstId).query(Integer.class).single());

        String createUser="legacy-create",createId=create(createUser,true,pack(createUser)).path("id").asText();
        jdbc.sql("UPDATE ai_mock_tasks SET task_type='AI_CREATE' WHERE resource_id=:id").param("id",createId).update();
        worker.run();
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id").param("id",createId).query(Integer.class).single());
    }

    @Test void allOperationsUseFrozenIsolatedSnapshots() throws Exception {
        String user="snapshot-A",pack=pack(user),other=pack("snapshot-B");
        JsonNode a=create(user,false,pack),b=create("snapshot-B",false,other),v=create(user,true,pack);
        jdbc.sql("UPDATE job_descriptions SET content='后来修改' WHERE id=:id").param("id",pack).update();
        jdbc.sql("UPDATE resume_files SET parsed_text='后来修改' WHERE id=:id").param("id",pack).update();
        jdbc.sql("UPDATE project_evidence_cards SET project_highlights='后来修改' WHERE id=:id").param("id",pack).update();
        worker.run();
        String textId=a.path("id").asText(),voiceId=v.path("id").asText();
        String q=getSession(user,textId,false).path("currentQuestion").path("id").asText();
        postJson(user,"/api/v1/mock-interviews/"+textId+"/answer","{\"questionId\":\""+q+"\",\"answerText\":\"我做了压测\",\"selfAssessment\":\"GOOD\"}",200);
        String vq=getSession(user,voiceId,true).path("currentQuestion").path("id").asText();
        postJson(user,"/api/v1/ai-mock-interviews/"+voiceId+"/questions/"+vq+"/confirm-answer","{\"answerText\":\"我进行了验证\"}",200);
        worker.run();
        assertEquals(Set.of("VOICE_PLAN","VOICE_QUESTION","VOICE_FEEDBACK","TEXT_MAIN_QUESTION","TEXT_FOLLOW_UP","TEXT_FEEDBACK"),new HashSet<>(requests.stream().map(r->r.path("operation").asText()).toList()));
        JsonNode frozen=json.readTree(jdbc.sql("SELECT material_snapshot FROM mock_interviews WHERE id=:id").param("id",textId).query(String.class).single());
        for(JsonNode req:requests) {
            JsonNode m=req.path("input").path("materials");
            assertFalse(m.toString().contains("后来修改"));
            assertFalse(m.toString().contains("snapshot-A") && m.toString().contains("snapshot-B"));
            if(m.path("jd").asText().contains(user)) assertEquals(frozen,m);
        }
        assertEquals(10,getSession(user,voiceId,true).path("totalQuestions").asInt());
        assertEquals(2,getSession(user,textId,false).path("questions").size());
        assertFalse(getSession("snapshot-B",b.path("id").asText(),false).path("currentQuestion").isNull());
    }

    @Test void retriesBackoffOwnershipAndManualNewCycle() throws Exception {
        String user="retry",session=create(user,false,pack(user)).path("id").asText();
        when(agent.simulate(anyString(),anyMap())).thenThrow(new SimulationException("MODEL_UNAVAILABLE"));
        for(int attempt=1;attempt<=3;attempt++) {
            worker.run();
            var task=tasks.latest(user,session);
            assertEquals(attempt,task.attempts());
            assertEquals(attempt<3?"PENDING":"FAILED",task.status());
            if(attempt<3) { assertNull(tasks.claim()); jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",task.id()).update(); }
        }
        var failed=tasks.latest(user,session);
        assertThrows(NoSuchElementException.class,()->tasks.retry("other",failed.id()));
        tasks.retry(user,failed.id());
        assertEquals(0,tasks.get(user,failed.id()).attempts());
        assertEquals("PENDING",tasks.get(user,failed.id()).status());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_tasks WHERE resource_id=:id").param("id",session).query(Integer.class).single());
    }

    @Test void reclaimingTaskClearsPreviousError() throws Exception {
        String user="reclaim",session=create(user,false,pack(user)).path("id").asText();
        when(agent.simulate(anyString(),anyMap())).thenThrow(new SimulationException("MODEL_UNAVAILABLE"));
        worker.run();
        var pending=tasks.latest(user,session);
        jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",pending.id()).update();
        var claimed=tasks.claim();
        assertEquals("PROCESSING",tasks.get(user,claimed.id()).status());
        assertEquals("",tasks.get(user,claimed.id()).error());
        tasks.fail(claimed,new SimulationException("MODEL_UNAVAILABLE"));
    }

    @Test void bothFlowsBlockBusyFinishAllowFailedAndFinishIdempotently() throws Exception {
        for(boolean voice:List.of(false,true)) {
            String user="finish-"+voice;
            JsonNode started=create(user,voice,pack(user)); String id=started.path("id").asText();
            String path="/api/v1/"+(voice?"ai-mock-interviews/":"mock-interviews/")+id+"/finish";
            assertTrue(postJson(user,path,"",503).toString().contains("AI 正在处理中"));
            var task=tasks.claim(); assertNotNull(task);
            assertTrue(postJson(user,path,"",503).toString().contains("AI 正在处理中"));
            tasks.fail(task,new SimulationException("INVALID_MODEL_OUTPUT"));
            JsonNode done=postJson(user,path,"",200);
            String field=voice?"finalInterviewId":"formalInterviewId";
            assertFalse(done.path(field).asText().isBlank());
            assertEquals(done.path(field),postJson(user,path,"",200).path(field));
            assertNull(tasks.latest(user,id));
            assertThrows(IllegalStateException.class,()->tasks.retry(user,task.id()));
        }
    }

    @Test void staleWorkerCannotWriteQuestion() throws Exception {
        String user="stale",session=create(user,false,pack(user)).path("id").asText();
        when(agent.simulate(anyString(),anyMap())).thenAnswer(call -> {
            jdbc.sql("UPDATE ai_mock_tasks SET locked_at=CURRENT_TIMESTAMP - INTERVAL '3' MINUTE WHERE resource_id=:id").param("id",session).update();
            return json.createObjectNode().put("questionText","不会被写入？");
        });
        var initial=tasks.claim();
        assertThrows(IllegalStateException.class,()->text.processTask(initial));
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id=:id").param("id",session).query(Integer.class).single());
        var old=tasks.claim(); assertNotNull(old);
        jdbc.sql("UPDATE mock_interviews SET status='FINISHED' WHERE id=:id").param("id",session).update();
        assertThrows(IllegalStateException.class,()->text.processTask(old));
    }

    @Test void expiredVoiceCancelsPendingAndReturnedModelCannotWritePlan() throws Exception {
        String user="expired",session=create(user,true,pack(user)).path("id").asText();
        when(agent.simulate(anyString(),anyMap())).thenAnswer(call -> {
            jdbc.sql("UPDATE ai_mock_interviews SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' MINUTE WHERE id=:id").param("id",session).update();
            return plan(json.readTree("[{\"projectName\":\"过期项目1\"},{\"projectName\":\"过期项目2\"},{\"projectName\":\"过期项目3\"},{\"projectName\":\"过期项目4\"}]"));
        });
        worker.run();
        assertEquals("TIME_EXPIRED",getSession(user,session,true).path("status").asText());
        assertNull(tasks.latest(user,session));
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE id=:id AND question_plan IS NOT NULL").param("id",session).query(Integer.class).single());
        String second=create(user,true,pack(user)).path("id").asText();
        jdbc.sql("UPDATE ai_mock_interviews SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' MINUTE WHERE id=:id").param("id",second).update();
        JsonNode active=json.readTree(mvc.perform(get("/api/v1/ai-mock-interviews").with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("TIME_EXPIRED",active.path("status").asText());
    }

    @Test void feedbackFailureStillAllowsSavingCompletedAnswers() throws Exception {
        for(boolean voice:List.of(false,true)) {
            String user="saved-answer-"+voice,id=create(user,voice,pack(user)).path("id").asText();
            worker.run();
            String q=getSession(user,id,voice).path("currentQuestion").path("id").asText();
            if(voice) postJson(user,"/api/v1/ai-mock-interviews/"+id+"/questions/"+q+"/confirm-answer","{\"answerText\":\"已确认的真实回答\"}",200);
            else postJson(user,"/api/v1/mock-interviews/"+id+"/answer","{\"questionId\":\""+q+"\",\"answerText\":\"已确认的真实回答\",\"selfAssessment\":\"GOOD\"}",200);
            doThrow(new SimulationException("INVALID_MODEL_OUTPUT")).when(agent).simulate(eq(voice?"VOICE_FEEDBACK":"TEXT_FEEDBACK"),anyMap());
            worker.run();
            assertEquals("FAILED",tasks.latest(user,id).status());
            if (voice) assertNotNull(getSession(user,id,true).path("currentQuestion").path("id").asText(null));
            var done=postJson(user,"/api/v1/"+(voice?"ai-mock-interviews/":"mock-interviews/")+id+"/finish","",200);
            String formal=done.path(voice?"finalInterviewId":"formalInterviewId").asText();
            assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id=:id AND answer_text='已确认的真实回答'").param("id",formal).query(Integer.class).single());
        }
    }

    @Test void audioRetryReusesTranscriptAndAsset() throws Exception {
        String user="audio-retry",id=create(user,true,pack(user)).path("id").asText();
        worker.run();
        String q=getSession(user,id,true).path("currentQuestion").path("id").asText();
        byte[] bytes=new byte[46];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,bytes,0,4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,bytes,8,4);
        bytes[44]=(byte)0xff;
        bytes[45]=0x7f;
        when(storage.download(anyString())).thenReturn(bytes);
        when(transcription.transcribe(eq(user),any(byte[].class),eq("audio/wav"))).thenReturn("我先压测再核对监控。");
        doThrow(new SimulationException("MODEL_TIMEOUT")).doReturn(json.createObjectNode().put("feedback","请补充压测证据。"))
            .when(agent).simulate(eq("VOICE_FEEDBACK"),anyMap());
        mvc.perform(multipart("/api/v1/ai-mock-interviews/"+id+"/questions/"+q+"/audio")
            .file(new org.springframework.mock.web.MockMultipartFile("file","answer.wav","audio/wav",bytes)).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk());
        worker.run();
        assertEquals("PENDING",tasks.latest(user,id).status());
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id").param("id",id).query(Integer.class).single());
        assertNotNull(getSession(user,id,true).path("currentQuestion").path("id").asText(null));
        jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP WHERE resource_id=:id").param("id",id).update();
        worker.run();
        verify(transcription,times(1)).transcribe(eq(user),any(byte[].class),eq("audio/wav"));
        verify(storage,times(1)).upload(anyString(),eq("audio/wav"),any(byte[].class));
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE ai_mock_interview_id=:id AND status='READY'").param("id",id).query(Integer.class).single());
        assertEquals("我先压测再核对监控。",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",q).query(String.class).single());
        assertEquals("请补充压测证据。",jdbc.sql("SELECT feedback FROM ai_mock_audio_assets WHERE question_id=:id").param("id",q).query(String.class).single());
        assertNull(tasks.latest(user,id));
    }

    @Test void silentVoiceAnswerAdvancesAsAnEmptyAnswer() throws Exception {
        String user="silent-audio",id=create(user,true,pack(user)).path("id").asText();
        worker.run();
        String q=getSession(user,id,true).path("currentQuestion").path("id").asText();
        byte[] bytes=new byte[46];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,bytes,0,4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,bytes,8,4);
        mvc.perform(multipart("/api/v1/ai-mock-interviews/"+id+"/questions/"+q+"/audio")
            .file(new org.springframework.mock.web.MockMultipartFile("file","answer.wav","audio/wav",bytes)).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk());
        worker.run();
        assertEquals("",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",q).query(String.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE question_id=:id").param("id",q).query(Integer.class).single());
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id").param("id",id).query(Integer.class).single());
        assertNull(tasks.latest(user,id));
        verifyNoInteractions(transcription);
        verify(agent,never()).simulate(eq("VOICE_FEEDBACK"),anyMap());
    }

    @Test void skippedVoiceAnswerAdvancesWithoutTranscription() throws Exception {
        String user="skip-audio",id=create(user,true,pack(user)).path("id").asText();
        worker.run();
        String q=getSession(user,id,true).path("currentQuestion").path("id").asText();
        postJson(user,"/api/v1/ai-mock-interviews/"+id+"/questions/"+q+"/skip-answer","",200);
        worker.run();
        assertEquals("",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",q).query(String.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_assets WHERE question_id=:id").param("id",q).query(Integer.class).single());
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id").param("id",id).query(Integer.class).single());
    }

    @Test void storedSilentWavAdvancesWithoutTranscription() throws Exception {
        String user="retried-silent-audio",id=create(user,true,pack(user)).path("id").asText();
        worker.run();
        String q=getSession(user,id,true).path("currentQuestion").path("id").asText();
        byte[] uploaded=new byte[46],silent=new byte[46];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,uploaded,0,4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,uploaded,8,4);
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,silent,0,4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,silent,8,4);
        uploaded[44]=(byte)0xff; uploaded[45]=0x7f;
        when(storage.download(anyString())).thenReturn(silent);
        mvc.perform(multipart("/api/v1/ai-mock-interviews/"+id+"/questions/"+q+"/audio")
            .file(new org.springframework.mock.web.MockMultipartFile("file","answer.wav","audio/wav",uploaded)).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk());
        worker.run();
        assertEquals("",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",q).query(String.class).single());
        verifyNoInteractions(transcription);
    }

    @Test void unrecognizedVoiceAdvancesAsAnEmptyAnswer() throws Exception {
        String user="unrecognized-audio",id=create(user,true,pack(user)).path("id").asText();
        worker.run();
        String q=getSession(user,id,true).path("currentQuestion").path("id").asText();
        byte[] bytes=new byte[46];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,bytes,0,4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.UTF_8),0,bytes,8,4);
        bytes[44]=(byte)0xff; bytes[45]=0x7f;
        when(storage.download(anyString())).thenReturn(bytes);
        when(transcription.transcribe(eq(user),any(byte[].class),eq("audio/wav"))).thenThrow(new IllegalStateException("no speech"));
        mvc.perform(multipart("/api/v1/ai-mock-interviews/"+id+"/questions/"+q+"/audio")
            .file(new org.springframework.mock.web.MockMultipartFile("file","answer.wav","audio/wav",bytes)).with(jwt().jwt(t->t.subject(user)))).andExpect(status().isOk());
        worker.run();
        assertEquals("",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",q).query(String.class).single());
        assertNull(tasks.latest(user,id));
    }

    @Test void concurrentFinishCreatesOnlyOneFormalRecord() throws Exception {
        for(boolean voice:List.of(false,true)) {
            String user="concurrent-finish-"+voice,id=create(user,voice,pack(user)).path("id").asText();
            tasks.fail(tasks.claim(),new SimulationException("INVALID_MODEL_OUTPUT"));
            String path="/api/v1/"+(voice?"ai-mock-interviews/":"mock-interviews/")+id+"/finish";
            var gate=new java.util.concurrent.CountDownLatch(1);
            try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<JsonNode> call=()->{gate.await();return postJson(user,path,"",200);};
                var first=executor.submit(call);var second=executor.submit(call);gate.countDown();
                String field=voice?"finalInterviewId":"formalInterviewId";
                assertEquals(first.get(5,java.util.concurrent.TimeUnit.SECONDS).path(field),second.get(5,java.util.concurrent.TimeUnit.SECONDS).path(field));
            }
            assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM interviews WHERE user_id=:user").param("user",user).query(Integer.class).single());
        }
    }

    @Test void expiredActionPersistsStateAndDoesNotCreateQuestion() throws Exception {
        String user="expired-action",id=create(user,true,pack(user)).path("id").asText();worker.run();
        String q=getSession(user,id,true).path("currentQuestion").path("id").asText();
        jdbc.sql("UPDATE ai_mock_interviews SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' MINUTE WHERE id=:id").param("id",id).update();
        assertEquals("TIME_EXPIRED",postJson(user,"/api/v1/ai-mock-interviews/"+id+"/questions/"+q+"/confirm-answer","{\"answerText\":\"超时回答\"}",200).path("status").asText());
        assertEquals("TIME_EXPIRED",jdbc.sql("SELECT status FROM ai_mock_interviews WHERE id=:id").param("id",id).query(String.class).single());
        assertEquals("",jdbc.sql("SELECT confirmed_answer_text FROM ai_mock_interview_questions WHERE id=:id").param("id",q).query(String.class).single());
    }

    @Test void newVoiceRejectsThreeSlotsWhileLegacyReadsThem() throws Exception {
        String user="three-plan",pack=pack(user),id=create(user,true,pack).path("id").asText();
        var three=plan(json.readTree("[{\"projectName\":\""+user+"项目1\"},{\"projectName\":\""+user+"项目2\"},{\"projectName\":\""+user+"项目3\"},{\"projectName\":\""+user+"项目4\"}]")); var list=(com.fasterxml.jackson.databind.node.ArrayNode)three.path("plan");
        while(list.size()>3) list.remove(list.size()-1);
        doReturn(three).when(agent).simulate(eq("VOICE_PLAN"),anyMap());
        worker.run();
        for(int attempt=0;attempt<2;attempt++) { jdbc.sql("UPDATE ai_mock_tasks SET available_at=CURRENT_TIMESTAMP WHERE resource_id=:id AND status='PENDING'").param("id",id).update(); worker.run(); }
        assertEquals("FAILED",tasks.latest(user,id).status());
        assertEquals(10,getSession(user,id,true).path("totalQuestions").asInt());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM ai_mock_interviews WHERE id=:id AND question_plan IS NOT NULL").param("id",id).query(Integer.class).single());
        String legacy=UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at,question_plan) VALUES(:id,:user,:pack,'公司','开发','一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE,:plan)")
            .param("id",legacy).param("user",user).param("pack",pack).param("plan",three.toString()).update();
        assertEquals(3,getSession(user,legacy,true).path("totalQuestions").asInt());
    }

    @Test void replacedLeaseRejectsLateFeedback() throws Exception {
        String user="stale-feedback",id=create(user,false,pack(user)).path("id").asText();worker.run();
        String q=getSession(user,id,false).path("currentQuestion").path("id").asText();
        postJson(user,"/api/v1/mock-interviews/"+id+"/answer","{\"questionId\":\""+q+"\",\"answerText\":\"真实回答\",\"selfAssessment\":\"GOOD\"}",200);
        doAnswer(call->{
            jdbc.sql("UPDATE ai_mock_tasks SET worker_token='replacement' WHERE resource_id=:id AND status='PROCESSING'").param("id",id).update();
            return json.createObjectNode().put("feedback","旧worker的反馈");
        }).when(agent).simulate(eq("TEXT_FEEDBACK"),anyMap());
        var next=tasks.claim();
        text.processTask(next); tasks.complete(next);
        var task=tasks.claim();
        assertThrows(IllegalStateException.class,()->text.processTask(task));
        assertEquals("",jdbc.sql("SELECT ai_feedback FROM mock_interview_questions WHERE id=:id").param("id",q).query(String.class).single());
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id=:id").param("id",id).query(Integer.class).single());
    }
}

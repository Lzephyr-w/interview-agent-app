package com.interviewagent.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.ai.AiMockTaskService;
import com.interviewagent.mock.MockInterviewService;
import com.interviewagent.material.ResumeFileStorage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "app.ai-mock-task.poll-ms=600000", "spring.datasource.url=jdbc:h2:mem:knowledge-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class KnowledgeControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired AiMockTaskService tasks;
    @Autowired MockInterviewService mock;
    @MockBean ResumeFileStorage storage;
    @MockBean AgentPythonClient model;

    @Test void categoryCanBeRenamedWithoutMovingItsDocuments() throws Exception {
        String user = UUID.randomUUID().toString();
        String category = id(mvc.perform(post("/api/v1/knowledge/categories").with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"React\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/knowledge/categories").with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Vue\"}"))
            .andExpect(status().isCreated());
        String document = upload(user, category, "react.md", "# React\n组件状态管理。");
        mvc.perform(put("/api/v1/knowledge/categories/{id}", category).with(jwt().jwt(t -> t.subject("stranger")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"前端框架\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/knowledge/categories/{id}", category).with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Vue\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/knowledge/categories/{id}", category).with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"前端框架\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("前端框架"));
        mvc.perform(get("/api/v1/knowledge/documents/{id}", document).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(jsonPath("$.categoryId").value(category));
    }

    @Test void knowledgeModeRetrievesOnlySelectedOwnedCategoryAndShowsSource() throws Exception {
        String user = UUID.randomUUID().toString();
        String category = id(mvc.perform(post("/api/v1/knowledge/categories").with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"React\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String other = id(mvc.perform(post("/api/v1/knowledge/categories").with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Vue\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String document = upload(user, category, "react.md", "# React 状态\nuseState 保存组件状态。\n# React 副作用\nuseEffect 同步外部状态。");
        upload(user, other, "vue.md", "# Vue\nVue 响应式系统。");
        mvc.perform(get("/api/v1/knowledge/documents/{id}", document).with(jwt().jwt(t -> t.subject("stranger"))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/knowledge/documents/{id}/content", document).with(jwt().jwt(t -> t.subject("stranger"))))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(t -> t.subject("stranger")))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("interviewPackageId", packageFor("stranger"), "sourceMode", "KNOWLEDGE", "categoryIds", new String[]{category}))))
            .andExpect(status().isNotFound());

        when(model.simulate(eq("TEXT_MAIN_QUESTION"), anyMap())).thenAnswer(call -> {
            Map<?,?> input = call.getArgument(1);
            assertTrue(input.get("knowledge").toString().contains("React"));
            assertFalse(input.get("knowledge").toString().contains("Vue 响应式"));
            return json.createObjectNode().put("questionText", "React 状态如何更新界面？");
        });
        String session = id(mvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("interviewPackageId", packageFor(user), "sourceMode", "KNOWLEDGE", "categoryIds", new String[]{category}))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sourceMode").value("KNOWLEDGE")).andReturn().getResponse().getContentAsString());
        var task = tasks.claim();
        assertNotNull(task);
        mock.processTask(task);
        tasks.complete(task);
        mvc.perform(get("/api/v1/mock-interviews/{id}", session).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion.sourceTitle").value("react.md"))
            .andExpect(jsonPath("$.currentQuestion.sourceLocation").isNotEmpty());
        mvc.perform(delete("/api/v1/knowledge/documents/{id}", document).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/mock-interviews/{id}", session).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(jsonPath("$.currentQuestion.sourceTitle").value("react.md"));
        verify(model).simulate(eq("TEXT_MAIN_QUESTION"), anyMap());
    }

    @Test void existingTextModeStillStartsWithoutKnowledge() throws Exception {
        String user = UUID.randomUUID().toString();
        when(model.simulate(eq("TEXT_MAIN_QUESTION"), anyMap())).thenAnswer(call -> {
            Map<?,?> input = call.getArgument(1);
            assertFalse(input.containsKey("knowledge"));
            return json.createObjectNode().put("questionText", "请介绍一次项目中的技术取舍？");
        });
        String session = id(mvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("interviewPackageId", packageFor(user)))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sourceMode").value("STANDARD"))
            .andReturn().getResponse().getContentAsString());
        var task = tasks.claim();
        assertNotNull(task);
        mock.processTask(task);
        tasks.complete(task);
        mvc.perform(get("/api/v1/mock-interviews/{id}", session).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuestion.sourceTitle").isEmpty());
    }

    @Test void textMockUsesSelectedMainCountAndAllowsAtMostTwoFollowups() throws Exception {
        String user = UUID.randomUUID().toString();
        String interviewPackage = packageFor(user);
        for (int invalid : new int[]{0, 11}) {
            mvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(t -> t.subject(user)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("interviewPackageId", interviewPackage, "totalQuestions", invalid))))
                .andExpect(status().isBadRequest());
        }
        AtomicInteger followups = new AtomicInteger();
        AtomicInteger mains = new AtomicInteger();
        when(model.simulate(eq("TEXT_MAIN_QUESTION"), anyMap())).thenAnswer(call ->
            json.createObjectNode().put("questionText", switch (mains.incrementAndGet()) {
                case 1 -> "请介绍一次 React 状态更新？";
                case 2 -> "描述一次前端构建性能优化？";
                default -> "你如何处理线上接口超时？";
            }));
        when(model.simulate(eq("TEXT_FOLLOW_UP"), anyMap())).thenAnswer(call ->
            json.createObjectNode().put("questionText", followups.incrementAndGet() == 1
                ? "你如何验证状态更新结果？" : "如果用户反馈页面卡顿，你会怎样定位？"));
        when(model.simulate(eq("TEXT_FEEDBACK"), anyMap()))
            .thenReturn(json.createObjectNode().put("feedback", "回答已记录。"));
        String session = id(mvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("interviewPackageId", interviewPackage, "totalQuestions", 1))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.totalQuestions").value(1))
            .andReturn().getResponse().getContentAsString());
        runTask();
        for (int index = 0; index < 3; index++) {
            JsonNode detail = json.readTree(mvc.perform(get("/api/v1/mock-interviews/{id}", session).with(jwt().jwt(t -> t.subject(user))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals(index == 0 ? "MAIN" : "FOLLOW_UP", detail.path("currentQuestion").path("questionKind").asText());
            mvc.perform(post("/api/v1/mock-interviews/{id}/answer", session).with(jwt().jwt(t -> t.subject(user)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                    "questionId", detail.path("currentQuestion").path("id").asText(),
                    "answerText", "我会检查状态和界面。", "selfAssessment", "UNCERTAIN"))))
                .andExpect(status().isOk());
            drainTasks();
        }
        mvc.perform(get("/api/v1/mock-interviews/{id}", session).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(jsonPath("$.totalQuestions").value(1))
            .andExpect(jsonPath("$.completedQuestions").value(1))
            .andExpect(jsonPath("$.currentQuestion").isEmpty())
            .andExpect(jsonPath("$.questions.length()").value(3));
        assertEquals(2, followups.get());
        JsonNode saved = json.readTree(mvc.perform(post("/api/v1/mock-interviews/{id}/finish", session).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String formalId = saved.path("formalInterviewId").asText();
        mvc.perform(delete("/api/v1/mock-interviews/{id}", session).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/interviews/{id}", formalId).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.questions.length()").value(3))
            .andExpect(jsonPath("$.questions[0].aiFeedback").value("回答已记录。"))
            .andExpect(jsonPath("$.questions[1].aiFeedback").value("回答已记录。"))
            .andExpect(jsonPath("$.questions[2].aiFeedback").value("回答已记录。"));

        String twoQuestions = id(mvc.perform(post("/api/v1/mock-interviews").with(jwt().jwt(t -> t.subject(user)))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("interviewPackageId", interviewPackage, "totalQuestions", 2))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.totalQuestions").value(2))
            .andReturn().getResponse().getContentAsString());
        runTask();
        for (int index = 0; index < 2; index++) {
            JsonNode detail = json.readTree(mvc.perform(get("/api/v1/mock-interviews/{id}", twoQuestions).with(jwt().jwt(t -> t.subject(user))))
                .andReturn().getResponse().getContentAsString());
            mvc.perform(post("/api/v1/mock-interviews/{id}/skip", twoQuestions).with(jwt().jwt(t -> t.subject(user)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("questionId", detail.path("currentQuestion").path("id").asText()))))
                .andExpect(status().isOk());
            drainTasks();
        }
        mvc.perform(get("/api/v1/mock-interviews/{id}", twoQuestions).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(jsonPath("$.completedQuestions").value(2))
            .andExpect(jsonPath("$.questions.length()").value(2))
            .andExpect(jsonPath("$.currentQuestion").isEmpty());
    }

    private void drainTasks() {
        for (int index = 0; index < 10; index++) {
            var task = tasks.claim();
            if (task == null) return;
            mock.processTask(task);
            tasks.complete(task);
        }
        fail("文本模拟产生了过多后台任务");
    }

    private void runTask() {
        var task = tasks.claim();
        assertNotNull(task);
        mock.processTask(task);
        tasks.complete(task);
    }

    private String upload(String user, String category, String name, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
        return id(mvc.perform(multipart("/api/v1/knowledge/documents").file(file).param("categoryId",category)
            .with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String packageFor(String user) {
        String resume = UUID.randomUUID().toString(), jd = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO resume_files(id,user_id,original_filename,content_type,size_bytes,object_path,parsed_text,parsed_status) VALUES(:id,:user,'resume.pdf','application/pdf',1,:path,'React 项目','READY')")
            .param("id",resume).param("user",user).param("path","resumes/"+resume+".pdf").update();
        jdbc.sql("INSERT INTO job_descriptions(id,user_id,company,role,content) VALUES(:id,:user,'A 公司','前端工程师','React 组件状态')")
            .param("id",jd).param("user",user).update();
        jdbc.sql("INSERT INTO interview_packages(id,user_id,company,role,interview_round,resume_file_id,job_description_id) VALUES(:id,:user,'A 公司','前端工程师','一面',:resume,:jd)")
            .param("id",id).param("user",user).param("resume",resume).param("jd",jd).update();
        return id;
    }

    private String id(String response) throws Exception { JsonNode parsed=json.readTree(response); return parsed.path("id").asText(); }
}

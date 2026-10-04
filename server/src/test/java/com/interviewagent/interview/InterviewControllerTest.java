package com.interviewagent.interview;

import com.interviewagent.ai.ReviewModelClient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"SUPABASE_URL=https://example.supabase.co", "spring.datasource.url=jdbc:h2:mem:interview-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.default-schema=PUBLIC", "spring.flyway.schemas=PUBLIC", "spring.flyway.create-schemas=false"})
@AutoConfigureMockMvc
class InterviewControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @MockBean ReviewModelClient model;
    @MockBean com.interviewagent.ai.AgentPythonClient agent;
    @MockBean com.interviewagent.ai.AiMockTaskWorker backgroundWorker;

    @org.junit.jupiter.api.AfterEach void noExternalAgentCalls() {
        org.mockito.Mockito.verifyNoInteractions(agent);
    }

    @Test void isolatesInterviewsQuestionsReviewsAndPackageAssociations() throws Exception {
        String packageA = packageFor("user-a");
        String interview = id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews").with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"A 公司\",\"role\":\"后端工程师\",\"interviewRound\":\"技术一面\",\"interviewTime\":\"2026-08-08T10:00:00+08:00\",\"interviewPackageId\":\"" + packageA + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\",\"notes\":\"现场记录\"}"))
            .andExpect(status().isCreated()).andReturn());
        String question = id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"如何处理缓存一致性？\",\"answerText\":\"说明了双删策略。\",\"selfAssessment\":\"UNCERTAIN\"}"))
            .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(get("/api/v1/interviews/{id}", interview).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/interviews/{id}/questions/{questionId}", interview, question).with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"x\",\"answerText\":\"x\",\"selfAssessment\":\"GOOD\"}")).andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews").with(jwt().jwt(token -> token.subject("user-b"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"B 公司\",\"role\":\"后端\",\"interviewRound\":\"一面\",\"interviewTime\":\"2026-08-08T10:00:00+08:00\",\"interviewPackageId\":\"" + packageA + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("user-b")))).andExpect(status().isNotFound());
    }

    @Test void reviewFailureDoesNotPersistAndInterviewDeletionCascades() throws Exception {
        String interview = createInterview("user-a", packageFor("user-a"));
        String question = id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目难点是什么？\",\"answerText\":\"待补充。\",\"selfAssessment\":\"UNANSWERED\"}"))
            .andExpect(status().isCreated()).andReturn());
        when(model.review(anyString(), anyInt(), anyMap())).thenThrow(new ReviewFailedException("AI 复盘服务请求失败，请稍后重试。"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadGateway());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id = :id").param("id", interview).query(Integer.class).single());

        jdbc.sql("INSERT INTO review_reports (id, interview_id, readiness, summary, weakness_tags) VALUES ('report-a', :interview, '准备不足', '待补充', '[]')").param("interview", interview).update();
        jdbc.sql("INSERT INTO question_reviews (id, review_report_id, interview_question_id, evaluation, answer_evidence, missing_evidence, improvement_action, recommended_answer_structure, possible_followups) VALUES ('question-review-a', 'report-a', :question, '待补充', '待补充', '待补充', '补充', 'STAR', '[]')").param("question", question).update();
        mockMvc.perform(delete("/api/v1/interviews/{id}", interview).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id = :id").param("id", interview).query(Integer.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id = :id").param("id", interview).query(Integer.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM question_reviews WHERE review_report_id = 'report-a'").query(Integer.class).single());
    }

    @Test void invalidOrOversizedAiOutputNeverPersists() throws Exception {
        String interview = createInterview("safe-output-user", packageFor("safe-output-user"));
        String question = id(mockMvc.perform(post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("safe-output-user"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目难点是什么？\",\"answerText\":\"待补充。\",\"selfAssessment\":\"UNANSWERED\"}"))
            .andExpect(status().isCreated()).andReturn());
        when(model.review(anyString(), anyInt(), anyMap())).thenReturn(objectMapper.readTree("{\"readiness\":\"基本准备\",\"summary\":\"" + "x".repeat(4_001) + "\",\"weaknessTags\":[],\"questionReviews\":[{\"questionId\":\"" + question + "\",\"evaluation\":\"待补充\",\"answerEvidence\":\"待补充\",\"missingEvidence\":\"待补充\",\"improvementAction\":\"待补充\",\"recommendedAnswerStructure\":\"待补充\",\"possibleFollowups\":[]}]}"));
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("safe-output-user"))))
            .andExpect(status().isBadGateway());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id=:id").param("id", interview).query(Integer.class).single());
    }

    @Test void validatesAndPersistsStructuredReview() throws Exception {
        String interview = createInterview("user-a", packageFor("user-a"));
        String question = id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目难点是什么？\",\"answerText\":\"我处理了缓存一致性。\",\"selfAssessment\":\"UNCERTAIN\"}"))
            .andExpect(status().isCreated()).andReturn());
        when(model.review(anyString(), anyInt(), anyMap())).thenReturn(objectMapper.readTree("{\"readiness\":\"基本准备\",\"summary\":\"回答有方向，但指标待补充。\",\"weaknessTags\":[\"项目深挖\"],\"questionReviews\":[{\"questionId\":\"" + question + "\",\"evaluation\":\"方向合理。\",\"answerEvidence\":\"说明了缓存一致性。\",\"missingEvidence\":\"指标待补充。\",\"improvementAction\":\"补充一次量化复盘。\",\"recommendedAnswerStructure\":\"背景-约束-方案-结果\",\"possibleFollowups\":[\"如何验证一致性？\"]}]}"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.readiness").value("基本准备")).andExpect(jsonPath("$.weaknessTags[0]").value("项目深挖"));
        mockMvc.perform(get("/api/v1/interviews/{id}", interview).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviews[0].questionReviews[0].missingEvidence").value(""));
    }

    @Test void retriesOnceWhenModelOutputFailsSchemaValidation() throws Exception {
        String interview = createInterview("retry-user", packageFor("retry-user"));
        String question = id(mockMvc.perform(post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject("retry-user"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目难点是什么？\",\"answerText\":\"缓存一致性。\",\"selfAssessment\":\"UNCERTAIN\"}"))
            .andExpect(status().isCreated()).andReturn());
        JsonNode valid = objectMapper.readTree("{\"readiness\":\"基本准备\",\"summary\":\"待补充。\",\"weaknessTags\":[],\"questionReviews\":[{\"questionId\":\"" + question + "\",\"evaluation\":\"待补充\",\"answerEvidence\":\"待补充\",\"missingEvidence\":\"待补充\",\"improvementAction\":\"补充指标\",\"recommendedAnswerStructure\":\"背景-方案-结果\",\"possibleFollowups\":[]}]}");
        when(model.review(anyString(), anyInt(), anyMap())).thenReturn(objectMapper.createObjectNode(), valid);
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject("retry-user"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.readiness").value("基本准备"));
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).review(anyString(), anyInt(), anyMap());
    }

    @Test void mapsShortReviewIdsAndRejectsUnknownOrDuplicateReferences() throws Exception {
        String user = "short-review-id-user";
        String interview = createInterview(user, packageFor(user));
        java.util.List<String> questions = new java.util.ArrayList<>();
        for (int index = 0; index < 2; index++) questions.add(id(mockMvc.perform(post("/api/v1/interviews/{id}/questions", interview).with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"项目难点是什么？\",\"answerText\":\"缓存一致性。\",\"selfAssessment\":\"UNCERTAIN\"}"))
            .andExpect(status().isCreated()).andReturn()));
        var output = objectMapper.createObjectNode().put("readiness", "基本准备").put("summary", "待补充");
        output.putArray("weaknessTags");
        var items = output.putArray("questionReviews");
        for (String reference : java.util.List.of("Q2", "Q1")) {
            var item = items.addObject().put("questionId", reference);
            for (String field : java.util.List.of("evaluation", "answerEvidence", "missingEvidence", "improvementAction", "recommendedAnswerStructure")) item.put(field, "待补充");
            item.putArray("possibleFollowups");
        }
        var unknown = output.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown.path("questionReviews").get(0)).put("questionId", "Q3");
        var duplicate = output.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate.path("questionReviews").get(0)).put("questionId", "Q1");
        var incomplete = output.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) incomplete.path("questionReviews")).remove(0);
        var wrongType = output.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongType.path("questionReviews").get(0)).put("evaluation", 7);
        for (var invalid : java.util.List.of(unknown, duplicate, incomplete, wrongType)) {
            when(model.review(anyString(), anyInt(), anyMap())).thenReturn(invalid);
            mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject(user)))).andExpect(status().isBadGateway());
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id=:id").param("id", interview).query(Integer.class).single());
        }
        org.mockito.Mockito.clearInvocations(model);
        when(model.review(anyString(), anyInt(), anyMap())).thenReturn(output);
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(token -> token.subject(user))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.questionReviews[*].questionId", org.hamcrest.Matchers.containsInAnyOrder(questions.toArray(String[]::new))));
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(model).review(prompt.capture(), anyInt(), anyMap());
        org.junit.jupiter.api.Assertions.assertTrue(prompt.getValue().contains("\"questionId\":\"Q1\""));
        questions.forEach(question -> org.junit.jupiter.api.Assertions.assertFalse(prompt.getValue().contains(question)));
    }

    @Test void distinguishesTextAndVoiceSimulationSources() throws Exception {
        String packageId = packageFor("user-a");
        String textInterview = createInterview("user-a", packageId);
        String voiceInterview = createInterview("user-a", packageId);
        jdbc.sql("UPDATE interviews SET interview_type = 'MOCK' WHERE id IN (:textId, :voiceId)")
            .param("textId", textInterview).param("voiceId", voiceInterview).update();
        jdbc.sql("INSERT INTO ai_mock_interviews (id, user_id, interview_package_id, company, role, interview_round, status, expires_at, final_interview_id) VALUES ('voice-session', 'user-a', :packageId, 'A 公司', '后端', '技术一面', 'FINISHED', CURRENT_TIMESTAMP, :interviewId)")
            .param("packageId", packageId).param("interviewId", voiceInterview).update();

        mockMvc.perform(get("/api/v1/interviews/{id}", textInterview).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.interview.simulationType").value("AI_TEXT"));
        mockMvc.perform(get("/api/v1/interviews/{id}", voiceInterview).with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.interview.simulationType").value("AI_VOICE"));
    }

    @Test void newContractIgnoresLegacyMissingEvidenceAndKeepsHistoryWithoutAutoRegeneration() throws Exception {
        String user = "review-compat-user", interview = createInterview(user, packageFor(user));
        String question = addQuestion(user, interview, "失败后怎么恢复？", "只重试整段上传，没有做过分片。", 0);
        String old = keepOldReport(interview, question);
        jdbc.sql("UPDATE interviews SET transcript = '原始转写保留' WHERE id = :id").param("id", interview).update();
        org.mockito.Mockito.doAnswer(call -> reviewOutput(call.getArgument(0))).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.questionReviews[0].missingEvidence").value(""));
        for (JsonNode legacy : java.util.List.of(objectMapper.getNodeFactory().textNode("通过概率" + "x".repeat(4_001)),
                objectMapper.createObjectNode().put("old", true), objectMapper.getNodeFactory().numberNode(7))) {
            org.mockito.Mockito.doAnswer(call -> {
                var output = reviewOutput(call.getArgument(0));
                ((com.fasterxml.jackson.databind.node.ObjectNode) output.path("questionReviews").get(0)).set("missingEvidence", legacy);
                return output;
            }).when(model).review(anyString(), anyInt(), anyMap());
            mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.questionReviews[0].missingEvidence").value(""));
        }
        org.mockito.Mockito.clearInvocations(model);
        mockMvc.perform(get("/api/v1/interviews/{id}", interview).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviews.length()").value(5)).andExpect(jsonPath("$.transcript").value("原始转写保留"));
        org.mockito.Mockito.verifyNoInteractions(model);
        JsonNode readBack = objectMapper.readTree(mockMvc.perform(get("/api/v1/interviews/{id}", interview).with(jwt().jwt(t -> t.subject(user)))).andReturn().getResponse().getContentAsString());
        JsonNode historic = java.util.stream.StreamSupport.stream(readBack.path("reviews").spliterator(), false).filter(report -> old.equals(report.path("id").asText())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("旧资料字段", historic.path("questionReviews").get(0).path("missingEvidence").asText());
        org.junit.jupiter.api.Assertions.assertEquals("旧资料字段", jdbc.sql("SELECT missing_evidence FROM question_reviews WHERE review_report_id = :id").param("id", old).query(String.class).single());
        org.junit.jupiter.api.Assertions.assertEquals("历史单段总结", jdbc.sql("SELECT summary FROM review_reports WHERE id = :id").param("id", old).query(String.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM question_reviews qr JOIN review_reports r ON r.id=qr.review_report_id WHERE r.interview_id=:id AND r.id<>:old AND qr.missing_evidence<>''").param("id", interview).param("old", old).query(Integer.class).single());
    }

    @Test void clipsOnlyPeripheralMaterialsAndKeepsEveryAnswerTailInSingleCall() throws Exception {
        String user = "review-material-budget-user", packageId = packageFor(user), interview = createInterview(user, packageId);
        jdbc.sql("UPDATE resume_files SET parsed_status='READY', parsed_text=:text WHERE id=(SELECT resume_file_id FROM interview_packages WHERE id=:id)")
            .param("text", "简历背景".repeat(10_000)).param("id", packageId).update();
        jdbc.sql("UPDATE job_descriptions SET content=:text WHERE id=(SELECT job_description_id FROM interview_packages WHERE id=:id)")
            .param("text", "岗位背景".repeat(10_000)).param("id", packageId).update();
        java.util.List<String> answers = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            String answer = "答".repeat(8_500) + "尾部否定" + index + "：没有做过分片";
            answers.add(answer); addQuestion(user, interview, "问题" + index, answer, index);
        }
        org.mockito.Mockito.doAnswer(call -> {
            String prompt = call.getArgument(0);
            JsonNode schema = objectMapper.valueToTree(call.getArgument(2));
            org.junit.jupiter.api.Assertions.assertTrue(prompt.length() + schema.toString().length() <= InterviewService.MAX_INPUT_CHARS);
            org.junit.jupiter.api.Assertions.assertEquals(4, schema.path("properties").size());
            org.junit.jupiter.api.Assertions.assertEquals(3, schema.path("properties").path("readiness").path("enum").size());
            org.junit.jupiter.api.Assertions.assertEquals(answers, inputQuestions(prompt).findValuesAsText("answerText"));
            return reviewOutput(prompt);
        }).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.questionReviews.length()").value(3));
        org.mockito.Mockito.verify(model).review(anyString(), anyInt(), anyMap());
    }

    @Test void batchesCompleteLongQuestionsAndSummarizesAllResultsInOriginalOrder() throws Exception {
        String user = "review-long-user", interview = createInterview(user, packageFor(user));
        java.util.List<String> ids = new java.util.ArrayList<>(), answers = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            String answer = "答".repeat(12_000) + "关键后半段" + index + "：没有做过状态机";
            answers.add(answer); ids.add(addQuestion(user, interview, "问题" + index, answer, index));
        }
        java.util.List<String> seenAnswers = new java.util.ArrayList<>(), summaryTopics = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            String prompt = call.getArgument(0);
            JsonNode schema = objectMapper.valueToTree(call.getArgument(2));
            org.junit.jupiter.api.Assertions.assertTrue(prompt.length() + schema.toString().length() <= InterviewService.MAX_INPUT_CHARS);
            org.junit.jupiter.api.Assertions.assertFalse(schema.path("additionalProperties").asBoolean(true));
            org.junit.jupiter.api.Assertions.assertTrue((int) call.getArgument(1) > 0 && (int) call.getArgument(1) <= 900);
            if (prompt.contains("\n全场逐题结果=")) {
                org.junit.jupiter.api.Assertions.assertEquals(3, schema.path("properties").size());
                org.junit.jupiter.api.Assertions.assertFalse(schema.path("properties").has("questionReviews"));
                JsonNode whole = inputData(prompt, "\n全场逐题结果=");
                summaryTopics.addAll(whole.findValuesAsText("questionText"));
                for (int index = 0; index < 3; index++) org.junit.jupiter.api.Assertions.assertTrue(whole.get(index).path("answerEvidence").asText().contains("关键后半段" + index));
            } else {
                JsonNode input = inputQuestions(prompt);
                JsonNode itemSchema = schema.path("properties").path("questionReviews").path("items");
                org.junit.jupiter.api.Assertions.assertFalse(itemSchema.path("additionalProperties").asBoolean(true));
                org.junit.jupiter.api.Assertions.assertEquals(input.findValuesAsText("questionId"), objectMapper.convertValue(itemSchema.path("properties").path("questionId").path("enum"), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}));
                seenAnswers.addAll(input.findValuesAsText("answerText"));
            }
            return reviewOutput(prompt);
        }).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.questionReviews[*].questionId", org.hamcrest.Matchers.contains(ids.toArray(String[]::new))));
        org.junit.jupiter.api.Assertions.assertEquals(answers, seenAnswers);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("问题0", "问题1", "问题2"), summaryTopics);
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(3)).review(anyString(), anyInt(), anyMap());
    }

    @Test void outputBudgetTriggersBatchesEvenWhenTextFitsAndAllCallsShareOneRepair() throws Exception {
        String user = "review-output-budget-user", interview = createInterview(user, packageFor(user));
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int index = 0; index < 7; index++) ids.add(addQuestion(user, interview, "问题" + index, "解释原理" + index, index));
        keepOldReport(interview, ids.getFirst());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) {
                String corrected = call.getArgument(0);
                org.junit.jupiter.api.Assertions.assertTrue(corrected.contains("整场唯一一次格式修正"));
                org.junit.jupiter.api.Assertions.assertFalse(corrected.contains("所有文本保持简短"));
                return reviewOutput(corrected);
            }
            return objectMapper.createObjectNode();
        }).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isBadGateway());
        org.junit.jupiter.api.Assertions.assertEquals(3, attempts.get()); // second batch cannot obtain another correction
        assertOnlyOldReport(interview);
    }

    @Test void invalidJsonCorrectionIncludesSafeReasonAndDoesNotSavePartialReview() throws Exception {
        String user = "review-json-correction-user", interview = createInterview(user, packageFor(user));
        String question = addQuestion(user, interview, "如何处理失败？", "原回答：没有做过自动恢复", 0);
        keepOldReport(interview, question);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        String reason = "AI 复盘模型输出JSON 未完整闭合（第3行，第8列），请重试。";
        org.mockito.Mockito.doAnswer(call -> {
            if (attempts.incrementAndGet() == 2) {
                String corrected = call.getArgument(0);
                org.junit.jupiter.api.Assertions.assertTrue(corrected.contains(reason));
                org.junit.jupiter.api.Assertions.assertTrue(corrected.contains("整场唯一一次格式修正"));
                org.junit.jupiter.api.Assertions.assertEquals("原回答：没有做过自动恢复", inputQuestions(corrected).get(0).path("answerText").asText());
            }
            throw new ReviewFailedException("INVALID_JSON", reason, null);
        }).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value(reason));
        org.junit.jupiter.api.Assertions.assertEquals(2, attempts.get());
        assertOnlyOldReport(interview);
    }

    @Test void nativeSchemaRejectsMalformedHttpBatchAfterOneRepairWithoutPartialSave() throws Exception {
        String user = "review-http-schema-user", interview = createInterview(user, packageFor(user));
        for (int index = 0; index < 13; index++) addQuestion(user, interview, "问题" + index, "原回答" + index, index);
        keepOldReport(interview, jdbc.sql("SELECT id FROM interview_questions WHERE interview_id=:id AND sort_order=0").param("id", interview).query(String.class).single());
        var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            requests.add(request);
            try {
                String reply = objectMapper.writeValueAsString(reviewOutput(request.path("messages").get(0).path("content").asText()));
                // Reproduce a gateway declaring stop for EOF, then a later near-tail syntax error.
                if (requests.size() == 1) reply = reply.substring(0, reply.length() - 1);
                if (requests.size() == 4) reply = reply.substring(0, reply.length() - 1) + ",}";
                byte[] body = objectMapper.writeValueAsBytes(java.util.Map.of("choices", java.util.List.of(java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of("content", reply)))));
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (Exception exception) { throw new java.io.IOException(exception); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var local = new ReviewModelClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort(), "key", "model");
            org.mockito.Mockito.doAnswer(call -> local.review(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(model).review(anyString(), anyInt(), anyMap());
            mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user))))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("JSON 语法错误")));
            org.junit.jupiter.api.Assertions.assertEquals(4, requests.size());
            for (JsonNode request : requests) {
                var format = request.path("response_format");
                org.junit.jupiter.api.Assertions.assertEquals("json_schema", format.path("type").asText());
                org.junit.jupiter.api.Assertions.assertTrue(format.path("json_schema").path("strict").asBoolean());
                var schema = format.path("json_schema").path("schema");
                org.junit.jupiter.api.Assertions.assertFalse(schema.path("additionalProperties").asBoolean(true));
                org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("questionReviews"), objectMapper.convertValue(schema.path("required"), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}));
                org.junit.jupiter.api.Assertions.assertFalse(schema.toString().contains("missingEvidence"));
                int length = request.path("messages").get(0).path("content").asText().length() + schema.toString().length();
                org.junit.jupiter.api.Assertions.assertTrue(length <= InterviewService.MAX_INPUT_CHARS);
            }
            assertOnlyOldReport(interview);
            org.junit.jupiter.api.Assertions.assertEquals(13, jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id=:id AND answer_text LIKE '原回答%'").param("id", interview).query(Integer.class).single());
        } finally { server.stop(0); }
    }

    @Test void anyBatchFailureOrTruncationLeavesOldReportAndOriginalAnswers() throws Exception {
        String user = "review-batch-failure-user", interview = createInterview(user, packageFor(user));
        String first = null;
        for (int index = 0; index < 7; index++) {
            String question = addQuestion(user, interview, "问题" + index, "原回答" + index, index);
            if (first == null) first = question;
        }
        keepOldReport(interview, first);
        for (String code : java.util.List.of("TRUNCATED", "HTTP_TIMEOUT", "CONNECTION")) {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doAnswer(call -> {
                if (attempts.incrementAndGet() == 2) throw new ReviewFailedException(code, "批次失败", null);
                return reviewOutput(call.getArgument(0));
            }).when(model).review(anyString(), anyInt(), anyMap());
            mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isBadGateway());
            org.junit.jupiter.api.Assertions.assertEquals(2, attempts.get());
            assertOnlyOldReport(interview);
            org.junit.jupiter.api.Assertions.assertEquals(7, jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id=:id AND answer_text LIKE '原回答%'").param("id", interview).query(Integer.class).single());
        }
    }

    @Test void rejectsOversizedSingleQuestionAndTooManyBatchesBeforeCallingModel() throws Exception {
        String user = "review-limit-user", interview = createInterview(user, packageFor(user));
        String question = addQuestion(user, interview, "如何转义？", "\\".repeat(20_000), 0);
        keepOldReport(interview, question);
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isBadGateway());
        assertOnlyOldReport(interview);
        org.mockito.Mockito.verifyNoInteractions(model);
        String other = createInterview(user, packageFor(user));
        for (int index = 0; index < InterviewService.MAX_BATCHES * InterviewService.MAX_QUESTIONS_PER_BATCH + 1; index++) addQuestion(user, other, "问题" + index, "回答", index);
        mockMvc.perform(post("/api/v1/interviews/{id}/review", other).with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isBadGateway());
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test void fullSummaryInputMustFitAndInvalidSummaryCannotSaveSuccessfulBatches() throws Exception {
        String user = "review-summary-budget-user", interview = createInterview(user, packageFor(user));
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int index = 0; index < 7; index++) ids.add(addQuestion(user, interview, "问题" + index, "原回答", index));
        keepOldReport(interview, ids.getFirst());
        org.mockito.Mockito.doAnswer(call -> {
            var root = reviewOutput(call.getArgument(0));
            for (JsonNode item : root.path("questionReviews")) for (String field : java.util.List.of("evaluation", "answerEvidence", "improvementAction", "recommendedAnswerStructure")) ((com.fasterxml.jackson.databind.node.ObjectNode) item).put(field, "中".repeat(1_500));
            return root;
        }).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isBadGateway());
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).review(anyString(), anyInt(), anyMap());
        assertOnlyOldReport(interview);
        org.mockito.Mockito.clearInvocations(model);
        org.mockito.Mockito.doAnswer(call -> {
            String prompt = call.getArgument(0);
            return prompt.contains("\n全场逐题结果=") ? objectMapper.createObjectNode() : reviewOutput(prompt);
        }).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isBadGateway());
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(4)).review(anyString(), anyInt(), anyMap());
        assertOnlyOldReport(interview);
    }

    @Test void rejectsStaleAnswerOrOwnershipAndNeverWaitsForModelInsideTransaction() throws Exception {
        String user = "review-snapshot-user", interview = createInterview(user, packageFor(user));
        String question = addQuestion(user, interview, "恢复机制？", "原回答", 0);
        keepOldReport(interview, question);
        org.mockito.Mockito.doAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.sql("UPDATE interview_questions SET answer_text='已修改回答' WHERE id=:id").param("id", question).update();
            return reviewOutput(call.getArgument(0));
        }).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isBadGateway());
        assertOnlyOldReport(interview);
        org.mockito.Mockito.doAnswer(call -> {
            jdbc.sql("UPDATE interviews SET user_id='another-review-owner' WHERE id=:id").param("id", interview).update();
            return reviewOutput(call.getArgument(0));
        }).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user)))).andExpect(status().isNotFound());
        assertOnlyOldReport(interview);
    }

    @Test void noReadyMaterialsStillReviewsAndPromptSeparatesSpeakerAndPracticeBoundaries() throws Exception {
        String user = "review-no-material-user", packageId = packageFor(user), interview = createInterview(user, packageId);
        jdbc.sql("UPDATE interview_packages SET resume_file_id=NULL, job_description_id=NULL WHERE id=:id").param("id", packageId).update();
        addQuestion(user, interview, "自我介绍", "负责前端项目。", 0);
        addQuestion(user, interview, "职业方向？", "希望继续做前端。", 1);
        addQuestion(user, interview, "【候选人反问】团队如何分工？", "【面试官回答】按业务分工。", 2);
        addQuestion(user, interview, "【面试官说明】岗位建议", "【面试官发言】先熟悉业务。", 3);
        org.mockito.Mockito.doAnswer(call -> reviewOutput(call.getArgument(0))).when(model).review(anyString(), anyInt(), anyMap());
        mockMvc.perform(post("/api/v1/interviews/{id}/review", interview).with(jwt().jwt(t -> t.subject(user))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.questionReviews.length()").value(4));
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(model).review(prompt.capture(), anyInt(), anyMap());
        for (String rule : java.util.List.of("无简历或证据卡仍正常复盘", "证据卡不是标准答案", "不将面试官发言评为候选人的错答", "没做过、不了解", "不把练习问题写成实际发生的追问", "600–1000", "4–6", "不推断音色")) org.junit.jupiter.api.Assertions.assertTrue(prompt.getValue().contains(rule), rule);
        org.junit.jupiter.api.Assertions.assertFalse(prompt.getValue().contains("missingEvidence,improvementAction"));
    }

    @Test void budgetCapsDeadlineCallsAndCorrectionsWithoutSleepOrNetwork() {
        var expired = new InterviewService.ReviewBudget(System.nanoTime() - 1);
        org.junit.jupiter.api.Assertions.assertThrows(ReviewFailedException.class, expired::beforeCall);
        var budget = new InterviewService.ReviewBudget();
        for (int index = 0; index < InterviewService.MAX_MODEL_CALLS; index++) org.junit.jupiter.api.Assertions.assertTrue(budget.beforeCall() <= InterviewService.TOTAL_BUDGET_SECONDS);
        org.junit.jupiter.api.Assertions.assertThrows(ReviewFailedException.class, budget::beforeCall);
        org.junit.jupiter.api.Assertions.assertTrue(budget.repair());
        org.junit.jupiter.api.Assertions.assertFalse(budget.repair());
    }

    private String addQuestion(String user, String interview, String question, String answer, int order) throws Exception {
        String id = id(createResource("/api/v1/interviews/" + interview + "/questions", user,
            objectMapper.writeValueAsString(new InterviewApi.QuestionRequest(question, answer, "UNCERTAIN"))));
        jdbc.sql("UPDATE interview_questions SET sort_order=:order WHERE id=:id").param("order", order).param("id", id).update();
        return id;
    }

    private String keepOldReport(String interview, String question) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO review_reports (id,interview_id,readiness,summary,weakness_tags) VALUES (:id,:interview,'基本准备','历史单段总结','[]')").param("id", id).param("interview", interview).update();
        jdbc.sql("INSERT INTO question_reviews (id,review_report_id,interview_question_id,evaluation,answer_evidence,missing_evidence,improvement_action,recommended_answer_structure,possible_followups) VALUES (:id,:report,:question,'旧评价','旧依据','旧资料字段','旧改进','旧组织','[]')")
            .param("id", java.util.UUID.randomUUID().toString()).param("report", id).param("question", question).update();
        return id;
    }

    private void assertOnlyOldReport(String interview) {
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id=:id AND summary='历史单段总结'").param("id", interview).query(Integer.class).single());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id=:id").param("id", interview).query(Integer.class).single());
    }

    private JsonNode inputData(String prompt, String label) throws Exception {
        String tail = prompt.substring(prompt.indexOf(label) + label.length());
        int end = tail.indexOf("\n辅助资料（仅背景，可裁剪）=");
        if (end < 0) end = tail.indexOf("\n上一次输出未通过格式校验");
        return objectMapper.readTree(end < 0 ? tail : tail.substring(0, end));
    }

    private JsonNode inputQuestions(String prompt) throws Exception { return inputData(prompt, "\n问答="); }

    private com.fasterxml.jackson.databind.node.ObjectNode reviewOutput(String prompt) throws Exception {
        String exampleLabel = "JSON 输出示例：";
        int exampleStart = prompt.indexOf(exampleLabel) + exampleLabel.length();
        JsonNode example = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(prompt.substring(exampleStart, prompt.indexOf('\n', exampleStart)));
        org.junit.jupiter.api.Assertions.assertTrue(example.isObject());
        var root = objectMapper.createObjectNode();
        if (!prompt.contains("本批逐题建议")) {
            root.put("readiness", "基本准备").put("summary", "你解释了当前实现范围。\n\n下一次先说明失败场景和方案边界。");
            root.putArray("weaknessTags");
        }
        if (!prompt.contains("\n全场逐题结果=")) {
            var items = root.putArray("questionReviews");
            for (JsonNode question : inputQuestions(prompt)) {
                String answer = question.path("answerText").asText();
                var item = items.addObject().put("questionId", question.path("questionId").asText()).put("evaluation", "你说明了当前实现范围。")
                    .put("answerEvidence", answer.substring(Math.max(0, answer.length() - 120)))
                    .put("improvementAction", "先说明失败场景，再说明恢复方式。").put("recommendedAnswerStructure", "场景→实现范围→边界");
                item.putArray("possibleFollowups");
            }
        }
        return root;
    }

    @Test void keepsAiSimulationQuestionsReadOnly() throws Exception {
        String packageId = packageFor("user-a");
        String textInterview = createInterview("user-a", packageId);
        String voiceInterview = createInterview("user-a", packageId);
        String textQuestion = id(mockMvc.perform(post("/api/v1/interviews/{id}/questions", textInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"文本题\",\"answerText\":\"文本答\",\"selfAssessment\":\"GOOD\"}"))
            .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/interviews/{id}/questions", voiceInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"语音题\",\"answerText\":\"语音答\",\"selfAssessment\":\"GOOD\"}"))
            .andExpect(status().isCreated());
        jdbc.sql("UPDATE interviews SET interview_type = 'MOCK' WHERE id IN (:textId, :voiceId)")
            .param("textId", textInterview).param("voiceId", voiceInterview).update();
        jdbc.sql("INSERT INTO ai_mock_interviews (id, user_id, interview_package_id, company, role, interview_round, status, expires_at, final_interview_id) VALUES ('readonly-voice-session', 'user-a', :packageId, 'A 公司', '后端', '技术一面', 'FINISHED', CURRENT_TIMESTAMP, :interviewId)")
            .param("packageId", packageId).param("interviewId", voiceInterview).update();

        mockMvc.perform(post("/api/v1/interviews/{id}/questions", textInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"新增题\",\"answerText\":\"新增答\",\"selfAssessment\":\"GOOD\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/interviews/{id}/questions/{questionId}", textInterview, textQuestion).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"修改题\",\"answerText\":\"修改答\",\"selfAssessment\":\"GOOD\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/interviews/{id}/questions/{questionId}", textInterview, textQuestion).with(jwt().jwt(token -> token.subject("user-a")))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/interviews/{id}/segment-transcript", textInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"transcript\":\"问：新增题\\n答：新增答\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/interviews/{id}/questions", voiceInterview).with(jwt().jwt(token -> token.subject("user-a"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"questionText\":\"新增题\",\"answerText\":\"新增答\",\"selfAssessment\":\"GOOD\"}")).andExpect(status().isBadRequest());
    }

    @Test void startsVoiceAnswerTimerOnlyWhenAnswerBegins() throws Exception {
        String packageId = packageFor("user-a");
        jdbc.sql("INSERT INTO ai_mock_interviews (id,user_id,interview_package_id,company,role,interview_round,status,expires_at) VALUES ('timer-session','user-a',:packageId,'A 公司','后端','技术一面','RUNNING',CURRENT_TIMESTAMP + INTERVAL '50' MINUTE)")
            .param("packageId", packageId).update();
        jdbc.sql("INSERT INTO ai_mock_interview_questions (id,ai_mock_interview_id,question_text,state,sort_order) VALUES ('timer-question','timer-session','请说明缓存策略。','OPEN',0)").update();

        mockMvc.perform(post("/api/v1/ai-mock-interviews/{id}/questions/{questionId}/start-answer", "timer-session", "timer-question").with(jwt().jwt(token -> token.subject("user-a"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questionId").value("timer-question")).andExpect(jsonPath("$.answerExpiresAt").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE id='timer-question' AND answer_started_at IS NOT NULL AND answer_expires_at IS NOT NULL").query(Integer.class).single());
    }

    private String packageFor(String user) throws Exception {
        String resumeFile = resumeFile(user);
        String jd = id(createResource("/api/v1/job-descriptions", user, "{\"company\":\"A 公司\",\"role\":\"后端\",\"content\":\"Spring\"}"));
        return id(createResource("/api/v1/interview-packages", user, "{\"company\":\"A 公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"resumeFileId\":\"" + resumeFile + "\",\"jobDescriptionId\":\"" + jd + "\",\"evidenceCardIds\":[]}"));
    }

    private String resumeFile(String user) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO resume_files (id, user_id, original_filename, content_type, size_bytes, object_path) VALUES (:id, :userId, 'resume.pdf', 'application/pdf', 8, :path)")
            .param("id", id).param("userId", user).param("path", "resumes/" + id + ".pdf").update();
        return id;
    }

    private String createInterview(String user, String packageId) throws Exception {
        return id(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interviews").with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"company\":\"A 公司\",\"role\":\"后端\",\"interviewRound\":\"技术一面\",\"interviewTime\":\"2026-08-08T10:00:00+08:00\",\"interviewPackageId\":\"" + packageId + "\",\"status\":\"PENDING_REVIEW\",\"result\":\"UNKNOWN\"}"))
            .andExpect(status().isCreated()).andReturn());
    }

    private MvcResult createResource(String path, String user, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(jwt().jwt(token -> token.subject(user))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
    }

    private String id(MvcResult result) throws Exception { JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()); return body.has("id") ? body.get("id").asText() : body.path("interview").path("id").asText(); }
}

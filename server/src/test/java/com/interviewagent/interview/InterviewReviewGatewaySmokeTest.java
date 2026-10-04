package com.interviewagent.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit opt-in only: exactly one real request, synthetic data, no application context or database. */
@EnabledIfSystemProperty(named = "review.gateway.smoke", matches = "true")
class InterviewReviewGatewaySmokeTest {
    @Test void oneSyntheticSixQuestionReviewUsesProductionSchemaAndValidation() throws Exception {
        Map<String, String> config = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(".env.local"))) {
            int separator = line.indexOf('=');
            if (separator < 0) continue;
            String name = line.substring(0, separator).trim();
            if (!Set.of("AI_REVIEW_API_URL", "AI_REVIEW_API_KEY", "AI_REVIEW_MODEL").contains(name)) continue;
            String value = line.substring(separator + 1).trim();
            if (value.length() > 1 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) value = value.substring(1, value.length() - 1);
            config.put(name, value);
        }
        for (String name : List.of("AI_REVIEW_API_URL", "AI_REVIEW_API_KEY", "AI_REVIEW_MODEL"))
            org.junit.jupiter.api.Assertions.assertFalse(config.getOrDefault(name, "").isBlank(), name + " is required");
        var json = new ObjectMapper();
        var model = new ReviewModelClient(json, config.get("AI_REVIEW_API_URL"), config.get("AI_REVIEW_API_KEY"), config.get("AI_REVIEW_MODEL"));
        var service = new InterviewService(null, json, model, org.mockito.Mockito.mock(PlatformTransactionManager.class));
        List<InterviewApi.InterviewQuestion> questions = List.of(
            question(1, "请做一个简短自我介绍。", "我做前端页面开发，最近主要负责表单和上传交互。先做业务页面，再做组件整理。复杂后端架构没有做过，希望往前端方向继续发展。"),
            question(2, "浏览器事件循环如何处理 Promise 和定时器？", "同步代码先执行，Promise 的 then 放到微任务里，setTimeout 属于后续任务。当前同步执行完后先处理微任务。我没有说明渲染时机，也没有实际举例。"),
            question(3, "上传失败后如何恢复？", "目前失败后只提示用户重新选择文件。我没有做断点续传和自动恢复；也没有记录分片进度。没有测过弱网下的行为，不能说已经解决了这类问题。"),
            question(4, "你下一份工作希望往什么方向发展？", "希望继续做前端，逐步接触复杂交互与性能优化。现在独立负责过小页面，但跨团队的方案协调还没做过。希望有人帮助我理解业务，而不是只接任务写页面。"),
            question(5, "【候选人反问】新同事入职后的主要工作是什么？", "面试官回答：先熟悉业务表单，然后参与上传模块。团队会安排同事带着熟悉现有代码。"),
            question(6, "【面试官说明】下一轮与团队职责", "面试官说明：下一轮讨论项目实践；本岗位主要负责前端，复杂存储服务由后端团队负责。此处没有要求候选人回答技术问题。")
        );
        String rules = (String) ReflectionTestUtils.getField(service, "REVIEW_RULES");
        @SuppressWarnings("unchecked") Set<String> tags = (Set<String>) ReflectionTestUtils.getField(service, "WEAKNESS_TAGS");
        String prompt = ReflectionTestUtils.invokeMethod(service, "prompt", rules + "\n固定弱项标签：" + String.join("、", tags), "无关联简历、JD或证据卡", questions, true);
        Map<String, Object> schema = ReflectionTestUtils.invokeMethod(service, "reviewSchema", questions, true, true);
        org.junit.jupiter.api.Assertions.assertTrue(prompt.length() + json.writeValueAsString(schema).length() <= InterviewService.MAX_INPUT_CHARS);
        // No retry loop: this test is the user's single explicitly authorized external request.
        var response = model.review(prompt, 240, schema);
        Object parsed = ReflectionTestUtils.invokeMethod(service, "parse", response, questions);
        org.junit.jupiter.api.Assertions.assertNotNull(parsed);
        System.out.println("review_smoke validJson=true fullCoverage=6 summaryChars=" + response.path("summary").asText().length()
            + " model=" + config.get("AI_REVIEW_MODEL") + " calls=1 databaseAccess=false");
    }

    private static InterviewApi.InterviewQuestion question(int number, String text, String answer) {
        return new InterviewApi.InterviewQuestion("synthetic-" + number, text, answer, "UNCERTAIN", number - 1, "");
    }
}

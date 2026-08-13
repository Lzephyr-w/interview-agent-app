package com.interviewagent.aimock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.interview.ReviewModelClient;
import com.interviewagent.interview.ReviewFailedException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class AiMockQuestionAgent {
    static final int QUESTION_LIMIT = 10;
    private static final Set<String> TYPES = Set.of("FUNDAMENTAL", "PROJECT", "SCENARIO", "BEHAVIORAL");
    private final ReviewModelClient model;
    private final ObjectMapper json;

    AiMockQuestionAgent(ReviewModelClient model, ObjectMapper json) { this.model = model; this.json = json; }

    List<PlanItem> plan(Materials m) {
        String prompt = """
            你是 AI 模拟面试的出题规划 Agent。根据当前用户授权的 JD、简历和证据卡规划整场 10 题，不直接出题。
            固定分布：第1-5题 FUNDAMENTAL（岗位核心技术/基础原理）；第6-9题 PROJECT（仅深挖简历或证据卡中的真实项目/经历）；第10题 SCENARIO 或 BEHAVIORAL（岗位真实场景、故障、性能、架构、协作或需求变化）。
            输入优先级：JD 岗位职责与技能 > 面试轮次 > 简历真实经历 > 证据卡。资料不足时基础题和第10题使用岗位相关通用问题；不得虚构候选人的项目、指标或技术细节。
            前端等岗位应按 JD/简历在浏览器、JavaScript/TypeScript、React、工程化、性能、安全中分散能力点。所有 competency 不重复；相邻题不得使用同一 projectName、technology 或 angle。项目资料不足时 projectName 留空，绝不能编造。
            禁止输出隐私信息、能力评级或招聘结论。只返回 JSON：{"plan":[{"order":1,"type":"FUNDAMENTAL","competency":"能力点","projectName":"真实项目名或空字符串","technology":"技术点或空字符串","angle":"问题角度"},...共10项]}
            面试包：%s / %s / %s
            JD：%s
            简历：%s
            证据卡：%s
            """.formatted(m.company, m.role, m.round, clip(m.jd, 8000), clip(m.resume, 12000), clip(m.cards.toString(), 12000));
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                List<PlanItem> plan = parsePlan(model.replyJson(prompt));
                if (plan.stream().anyMatch(item -> !item.projectName.isBlank() && !grounded(item.projectName, m))) throw invalidPlan();
                return plan;
            } catch (ReviewFailedException exception) { throw exception;
            } catch (RuntimeException exception) { prompt += "\n上次计划 JSON 非法或分布/去重不合规，请完整重做 10 项计划。"; }
        }
        throw invalidPlan();
    }

    QuestionDraft generate(Materials m, PlanItem item, List<QuestionHistory> history) {
        String prompt = """
            你是 AI 模拟面试的出题执行 Agent。只能依据当前用户授权的 JD、简历和证据卡，必须严格执行当前计划槽位，只出一道中文问题。
            资料优先级：JD 岗位职责与技能 > 面试轮次 > 简历真实经历 > 证据卡。PROJECT 只能引用输入中真实存在的项目/经历；禁止虚构项目、数据、技术细节。资料不足时不要反复要求“介绍项目”，应提出岗位相关、可独立回答的问题。
            禁止与全部历史问题语义重复；禁止重复考察能力点；禁止连续围绕同一项目、同一技术名词或同一种问句开头。禁止输出隐私信息、能力评级或招聘结论。题型必须等于计划 type，能力点必须等于计划 competency。
            只返回 JSON：{"questionText":"问题","type":"FUNDAMENTAL|PROJECT|SCENARIO|BEHAVIORAL","competency":"能力点","projectName":"真实项目名或空字符串","technology":"技术点或空字符串"}
            当前计划：%s
            面试包：%s / %s / %s
            JD：%s
            简历：%s
            证据卡：%s
            全部历史问题（含跳过题）：%s
            """.formatted(item, m.company, m.role, m.round, clip(m.jd, 8000), clip(m.resume, 12000), clip(m.cards.toString(), 12000), history);
        String lastError = "AI 返回格式无效";
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                QuestionDraft draft = parseQuestion(model.replyJson(prompt));
                lastError = qualityError(draft, item, history);
                if (lastError == null) return draft;
            } catch (ReviewFailedException exception) { throw exception;
            } catch (RuntimeException exception) {
                lastError = "AI 返回 JSON 非法或缺少必填字段";
            }
            prompt += "\n上次结果被拒绝：" + lastError + "。请严格修正后重新返回 JSON。";
        }
        throw new IllegalStateException(lastError + "；已重试 3 次，未写入题目，请稍后重试。");
    }

    List<PlanItem> parsePlan(JsonNode root) {
        JsonNode items = root.path("plan");
        if (!items.isArray() || (items.size() != QUESTION_LIMIT && items.size() != 3)) throw invalidPlan();
        boolean legacyThree = items.size() == 3;
        List<PlanItem> result = new ArrayList<>();
        Set<String> competencies = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode node = items.get(i);
            PlanItem item = new PlanItem(requiredInt(node, "order"), required(node, "type"), required(node, "competency"), text(node, "projectName"), text(node, "technology"), required(node, "angle"));
            String expected = legacyThree ? (i == 0 ? "FUNDAMENTAL" : i == 1 ? "PROJECT" : item.type) : (i < 5 ? "FUNDAMENTAL" : i < 9 ? "PROJECT" : item.type);
            int finalIndex = legacyThree ? 2 : 9;
            if (item.order != i + 1 || !TYPES.contains(item.type) || !expected.equals(item.type) || (i == finalIndex && !Set.of("SCENARIO", "BEHAVIORAL").contains(item.type)) || !competencies.add(normalize(item.competency))) throw invalidPlan();
            if (!result.isEmpty()) {
                PlanItem previous = result.getLast();
                if (same(previous.projectName, item.projectName) || same(previous.technology, item.technology) || same(previous.angle, item.angle)) throw invalidPlan();
            }
            result.add(item);
        }
        return List.copyOf(result);
    }

    String serialize(List<PlanItem> plan) {
        try { return json.writeValueAsString(Map.of("plan", plan)); }
        catch (Exception exception) { throw invalidPlan(); }
    }

    List<PlanItem> deserialize(String value) {
        try { return parsePlan(json.readTree(value)); }
        catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw invalidPlan(); }
    }

    static String qualityError(QuestionDraft candidate, PlanItem plan, List<QuestionHistory> history) {
        if (!candidate.type.equals(plan.type)) return "题型未按计划";
        if (!normalize(candidate.competency).equals(normalize(plan.competency))) return "考察能力点未按计划";
        if (!normalize(candidate.projectName).equals(normalize(plan.projectName))) return "项目名未按计划或可能为虚构";
        if (!normalize(candidate.technology).equals(normalize(plan.technology))) return "技术点未按计划";
        String normalized = normalize(candidate.questionText);
        if (history.stream().anyMatch(old -> normalize(old.questionText).equals(normalized) || similarity(normalize(old.questionText), normalized) >= 0.65)) return "问题与本场历史问题重复";
        if (history.stream().anyMatch(old -> same(old.competency, candidate.competency))) return "考察能力点重复";
        if (!history.isEmpty()) {
            QuestionHistory previous = history.getLast();
            if (same(previous.projectName, candidate.projectName)) return "连续使用同一项目";
            if (same(previous.technology, candidate.technology)) return "连续使用同一技术点";
            if (sentencePattern(previous.questionText).equals(sentencePattern(candidate.questionText))) return "连续使用同一种问题句式";
        }
        return null;
    }

    private static QuestionDraft parseQuestion(JsonNode node) {
        String type = required(node, "type");
        if (!TYPES.contains(type)) throw new IllegalArgumentException();
        return new QuestionDraft(required(node, "questionText", 800), type, required(node, "competency", 120), text(node, "projectName", 120), text(node, "technology", 120));
    }
    private static String required(JsonNode node, String field) { return required(node, field, 200); }
    private static String required(JsonNode node, String field, int maximum) { String value = text(node, field, maximum); if (value.isBlank()) throw new IllegalArgumentException(); return value; }
    private static int requiredInt(JsonNode node, String field) { if (!node.path(field).canConvertToInt()) throw new IllegalArgumentException(); return node.path(field).asInt(); }
    private static String text(JsonNode node, String field) { return text(node, field, 200); }
    private static String text(JsonNode node, String field, int maximum) { String value = node.path(field).isTextual() ? node.path(field).asText().trim() : ""; if (value.length() > maximum) throw new IllegalArgumentException(); return value; }
    private static IllegalStateException invalidPlan() { return new IllegalStateException("AI 出题计划 JSON 非法或字段缺失；未创建题目，请稍后重试。"); }
    private static boolean same(String left, String right) { return !normalize(left).isBlank() && normalize(left).equals(normalize(right)); }
    private static String normalize(String text) { return text == null ? "" : text.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT); }
    private static String sentencePattern(String text) { String value = normalize(text); return value.substring(0, Math.min(6, value.length())); }
    private static double similarity(String left, String right) { if (left.length() < 2 || right.length() < 2) return 0; Set<String> a = grams(left), b = grams(right), both = new HashSet<>(a); both.retainAll(b); Set<String> all = new HashSet<>(a); all.addAll(b); return all.isEmpty() ? 0 : (double) both.size() / all.size(); }
    private static Set<String> grams(String text) { Set<String> result = new HashSet<>(); for (int i = 1; i < text.length(); i++) result.add(text.substring(i - 1, i + 1)); return result; }
    private static String clip(String value, int limit) { if (value == null || value.isBlank()) return "待补充"; return value.length() > limit ? value.substring(0, limit) + "（已截断）" : value; }
    private static boolean grounded(String value, Materials m) { String source = normalize(m.resume + " " + String.join(" ", m.cards)); return source.contains(normalize(value)); }

    record Materials(String company, String role, String round, String jd, String resume, List<String> cards) {}
    record PlanItem(int order, String type, String competency, String projectName, String technology, String angle) {}
    record QuestionDraft(String questionText, String type, String competency, String projectName, String technology) {}
    record QuestionHistory(String questionText, String type, String competency, String projectName, String technology) {}
}

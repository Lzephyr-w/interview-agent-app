package com.interviewagent.aimock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.ai.SimulationContract;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class AiMockQuestionAgent {
    static final int QUESTION_LIMIT = 10;
    private static final Set<String> TYPES = Set.of("FUNDAMENTAL", "PROJECT", "SCENARIO", "BEHAVIORAL");
    private final AgentPythonClient model;
    private final ObjectMapper json;

    AiMockQuestionAgent(AgentPythonClient model, ObjectMapper json) { this.model = model; this.json = json; }

    List<PlanItem> plan(JsonNode materials) {
        JsonNode result=model.simulate("VOICE_PLAN",Map.of("materials",materials,"history",List.of()));
        List<PlanItem> plan=parsePlan(result,false);
        if (plan.stream().anyMatch(item -> !item.projectName.isBlank() && !grounded(item.projectName,materials))) throw SimulationContract.invalid();
        return plan;
    }

    QuestionDraft generate(JsonNode materials, PlanItem item, List<QuestionHistory> history) {
        JsonNode result=model.simulate("VOICE_QUESTION",Map.of("materials",materials,"slot",item,"history",history));
        SimulationContract.result("VOICE_QUESTION",result);
        QuestionDraft draft=parseQuestion(result);
        if ((!draft.projectName.isBlank() && !grounded(draft.projectName,materials)) || qualityError(draft,item,history)!=null) throw SimulationContract.invalid();
        return draft;
    }

    List<PlanItem> parsePlan(JsonNode root, boolean legacy) {
        if (!legacy) SimulationContract.result("VOICE_PLAN",root);
        JsonNode items = legacy && root.isArray() ? root : root.path("plan");
        if (legacy && items.isTextual()) {
            try { items=json.readTree(items.asText()); } catch(Exception ignored) { throw invalidPlan(); }
        }
        if (!items.isArray() || (items.size()!=QUESTION_LIMIT && !(legacy && items.size()==3))) throw invalidPlan();
        List<PlanItem> result=new ArrayList<>();
        Set<String> competencies=new HashSet<>();
        for(int i=0;i<items.size();i++) {
            JsonNode node=items.get(i);
            PlanItem item=new PlanItem(requiredInt(node,"order"),required(node,"type").toUpperCase(Locale.ROOT),required(node,"competency"),projectName(node),text(node,"technology"),required(node,"angle"));
            if(item.order!=i+1 || !TYPES.contains(item.type) || !competencies.add(normalize(item.competency))) throw invalidPlan();
            if (!legacy) {
                if (!(i<5 ? item.type.equals("FUNDAMENTAL") : i<9 ? item.type.equals("PROJECT") : Set.of("SCENARIO","BEHAVIORAL").contains(item.type))) throw invalidPlan();
                if(i>0) {
                    PlanItem previous=result.get(i-1);
                    if(same(previous.projectName,item.projectName)||same(previous.technology,item.technology)||same(previous.angle,item.angle)) throw invalidPlan();
                }
            }
            result.add(item);
        }
        return List.copyOf(result);
    }

    String serialize(List<PlanItem> plan) {
        try { return json.writeValueAsString(Map.of("plan", plan)); }
        catch (Exception exception) { throw invalidPlan(); }
    }

    List<PlanItem> deserialize(String value, boolean legacy) {
        try { return parsePlan(json.readTree(value),legacy); }
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
        String type = required(node, "type").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new IllegalArgumentException();
        return new QuestionDraft(required(node, "questionText", 800), type, required(node, "competency", 120), projectName(node, 120), text(node, "technology", 120));
    }
    private static String required(JsonNode node, String field) { return required(node, field, 200); }
    private static String required(JsonNode node, String field, int maximum) { String value = text(node, field, maximum); if (value.isBlank()) throw new IllegalArgumentException(); return value; }
    private static int requiredInt(JsonNode node, String field) { if (node.path(field).canConvertToInt()) return node.path(field).asInt(); if (node.path(field).isTextual()) try { return Integer.parseInt(node.path(field).asText().trim()); } catch (NumberFormatException ignored) {} throw new IllegalArgumentException(); }
    private static String text(JsonNode node, String field) { return text(node, field, 200); }
    private static String text(JsonNode node, String field, int maximum) { String value = node.path(field).isTextual() ? node.path(field).asText().trim() : ""; if (value.length() > maximum) throw new IllegalArgumentException(); return value; }
    private static String projectName(JsonNode node) { return projectName(node, 200); }
    private static String projectName(JsonNode node, int maximum) { String value = text(node, "projectName", maximum); return Set.of("待补充", "暂无", "无", "未提供", "N/A", "NA").contains(value.toUpperCase(Locale.ROOT)) ? "" : value; }
    private static RuntimeException invalidPlan() { return SimulationContract.invalid(); }
    private static boolean same(String left, String right) { return !normalize(left).isBlank() && normalize(left).equals(normalize(right)); }
    private static String normalize(String text) { return text == null ? "" : text.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT); }
    private static String sentencePattern(String text) { String value = normalize(text); return value.substring(0, Math.min(6, value.length())); }
    private static double similarity(String left, String right) { if (left.length() < 2 || right.length() < 2) return 0; Set<String> a = grams(left), b = grams(right), both = new HashSet<>(a); both.retainAll(b); Set<String> all = new HashSet<>(a); all.addAll(b); return all.isEmpty() ? 0 : (double) both.size() / all.size(); }
    private static Set<String> grams(String text) { Set<String> result = new HashSet<>(); for (int i = 1; i < text.length(); i++) result.add(text.substring(i - 1, i + 1)); return result; }
    private static boolean grounded(String value, JsonNode m) {
        if(normalize(value).isBlank()) return false;
        for (JsonNode card:m.path("cards")) if (same(value,card.path("projectName").asText())) return true;
        return normalize(m.path("resume").asText()).contains(normalize(value));
    }

    record PlanItem(int order, String type, String competency, String projectName, String technology, String angle) {}
    record QuestionDraft(String questionText, String type, String competency, String projectName, String technology) {}
    record QuestionHistory(String questionText, String type, String competency, String projectName, String technology) {}
}

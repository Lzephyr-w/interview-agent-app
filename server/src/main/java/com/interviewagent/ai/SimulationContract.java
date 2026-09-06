package com.interviewagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Wire shape only. Session-specific business validation remains in the services. */
public final class SimulationContract {
    public static final Set<String> OPERATIONS = Set.of("VOICE_PLAN","VOICE_QUESTION","VOICE_FEEDBACK","TEXT_MAIN_QUESTION","TEXT_FOLLOW_UP","TEXT_FEEDBACK");
    public static final Set<String> CODES = Set.of("INVALID_REQUEST","MODEL_TIMEOUT","MODEL_UNAVAILABLE","INVALID_MODEL_OUTPUT","UNAUTHORIZED","INTERNAL_ERROR");
    private static final int QUESTION_QUALITY_MAX = 200;
    private static final int PLAN_LABEL_MAX = 80;
    private SimulationContract() {}
    public static void fields(JsonNode node, String... names) {
        Set<String> actual = new HashSet<>();
        if (node == null || !node.isObject()) throw invalid();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(names))) throw invalid();
    }
    public static String text(JsonNode node, String field, int max, boolean empty) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().length()>max || (!empty && value.asText().isBlank())) throw invalid();
        return value.asText().trim();
    }
    public static void input(String operation, JsonNode data) {
        try {
            if (!OPERATIONS.contains(operation)) throw invalid();
            if (operation.equals("VOICE_QUESTION")) fields(data,"materials","history","slot");
            else if (operation.contains("FEEDBACK") || operation.equals("TEXT_FOLLOW_UP")) fields(data,"materials","history","questionText","answer");
            else fields(data,"materials","history");
            JsonNode m = data.path("materials");
            Set<String> materialFields = new HashSet<>(); m.fieldNames().forEachRemaining(materialFields::add);
            Set<String> baseMaterials = Set.of("company","role","round","jd","resume","cards");
            if (!materialFields.equals(baseMaterials) && !materialFields.equals(Set.of("company","role","round","jd","resume","cards","experienceAnchors"))) throw invalid();
            for (String f : List.of("company","role","round")) text(m,f,200,false);
            text(m,"jd",8000,false); text(m,"resume",12000,false);
            if (!m.path("cards").isArray() || m.path("cards").size()>30 || m.path("cards").toString().length()>16000) throw invalid();
            for (JsonNode card:m.path("cards")) {
                fields(card,"projectName","projectDescriptionAndResponsibilities","projectHighlights","technologyStack");
                text(card,"projectName",120,false);
                for (String f:List.of("projectDescriptionAndResponsibilities","projectHighlights","technologyStack")) text(card,f,4000,false);
            }
            if (m.has("experienceAnchors")) {
                if (!m.path("experienceAnchors").isArray() || m.path("experienceAnchors").size()>40) throw invalid();
                for (JsonNode anchor:m.path("experienceAnchors")) if (!anchor.isTextual() || anchor.asText().isBlank() || anchor.asText().length()>120) throw invalid();
            }
            if (!data.path("history").isArray() || data.path("history").size()>10) throw invalid();
            for (JsonNode h:data.path("history")) {
                fields(h,"questionText","type","competency","projectName","technology");
                text(h,"questionText",800,false);
                for (String f:List.of("type","competency","projectName","technology")) text(h,f,120,true);
            }
            if (data.has("slot")) slot(data.path("slot"));
            if (data.has("answer")) { text(data,"questionText",800,false); text(data,"answer",operation.equals("VOICE_FEEDBACK")?40000:8000,true); }
        } catch (RuntimeException error) { throw new SimulationException("INVALID_REQUEST"); }
    }
    private static void metadata(JsonNode n) {
        if (!Set.of("FUNDAMENTAL","PROJECT","SCENARIO","BEHAVIORAL").contains(text(n,"type",120,false))) throw invalid();
        text(n,"competency",120,false); text(n,"projectName",120,true); text(n,"technology",120,true);
    }
    private static void slot(JsonNode n) {
        fields(n,"order","type","competency","projectName","technology","angle");
        if (!n.path("order").isIntegralNumber() || !n.path("order").canConvertToInt() || n.path("order").asInt()<1 || n.path("order").asInt()>10) throw invalid();
        metadata(n); planText(text(n,"competency",120,false)); planText(text(n,"technology",120,true)); planText(text(n,"angle",200,false));
    }
    public static void result(String operation, JsonNode result) {
        if (operation.equals("VOICE_PLAN")) {
            fields(result,"plan");
            if (!result.path("plan").isArray() || result.path("plan").size()!=10) throw invalid();
            result.path("plan").forEach(SimulationContract::slot);
        } else if (operation.equals("VOICE_QUESTION")) {
            fields(result,"questionText","type","competency","projectName","technology");
            question(text(result,"questionText",800,false)); metadata(result);
        } else {
            String f=operation.contains("FEEDBACK")?"feedback":"questionText";
            fields(result,f);
            String value=text(result,f,f.equals("feedback")?600:800,false);
            if (f.equals("feedback")) feedback(value); else question(value);
        }
    }
    public static void modelResult(String operation, JsonNode result) {
        try { result(operation,result); }
        catch (SimulationException error) { throw retryableInvalid(); }
    }
    public static void question(String value) {
        if (value.length()>QUESTION_QUALITY_MAX || questionMarks(value)>1) throw invalid();
    }
    public static void feedback(String value) {
        int sentences=0; boolean terminal=false;
        for(int index=0;index<value.length();index++) {
            boolean current="。！？!?".indexOf(value.charAt(index))>=0;
            if(current&&!terminal&&++sentences>2) throw invalid();
            terminal=current;
        }
    }
    private static void planText(String value) {
        if(value.length()>PLAN_LABEL_MAX || questionMarks(value)>0) throw invalid();
    }
    private static int questionMarks(String value) { int count=0; for(int index=0;index<value.length();index++) if(value.charAt(index)=='?'||value.charAt(index)=='？') count++; return count; }
    public static SimulationException invalid() { return new SimulationException("INVALID_MODEL_OUTPUT"); }
    public static SimulationException retryableInvalid() { return new SimulationException("INVALID_MODEL_OUTPUT",true); }
}

package com.interviewagent.interview;

import static com.interviewagent.interview.InterviewApi.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Suggestions never mutate ASR text; only explicitly accepted, validated spans affect display. */
final class ImportCorrections {
    static List<ImportCorrection> parse(JsonNode root,ImportAnalysis.Block block,List<ImportTurn> turns,String resume) {
        if(!root.has("corrections")) return List.of();
        if(!root.path("corrections").isArray() || root.path("corrections").size()>64) throw new IllegalArgumentException("模型纠错数组无效或超过64项。");
        Set<Integer> allowed=new HashSet<>(block.context()); allowed.addAll(block.added());
        List<ImportCorrection> result=new ArrayList<>();
        for(JsonNode n:root.path("corrections")) {
            int id=integer(n,"turnId");
            if(!allowed.contains(id)) throw new IllegalArgumentException("纠错引用当前片段之外的发言编号："+id+"。");
            int start=integer(n,"start"),end=integer(n,"end"),es=integer(n,"evidenceStart"),ee=integer(n,"evidenceEnd");
            String original=string(n,"original"),replacement=string(n,"replacement"),source=string(n,"evidenceSource"),evidence=string(n,"evidence"),reason=string(n,"reason");
            Integer eid=n.path("evidenceTurnId").isNull()||!n.has("evidenceTurnId")?null:integer(n,"evidenceTurnId");
            if("CONTEXT".equals(source) && (eid==null||!allowed.contains(eid))) throw new IllegalArgumentException("纠错上下文证据编号无效。");
            String key=id+":"+start+":"+end+":"+original+":"+replacement;
            String error="";
            if(!span(turns.get(id).text(),start,end,original)) error="原词与来源区间不符。";
            else if(original.equals(replacement)||!term(original)||!term(replacement)) error="仅允许短名称或术语替换，不能改写句子。";
            else if(protectedText(original)||protectedText(replacement)) error="不能通过纠错修改数字、否定或确定程度。";
            else {
                String reference="RESUME".equals(source)?resume:"CONTEXT".equals(source)?turns.get(eid).text():"";
                if(!span(reference,es,ee,evidence)||!evidence.contains(replacement)) error="纠错证据与原文不符或不包含建议词。";
            }
            result.add(new ImportCorrection(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(),id,start,end,original,replacement,source,eid,es,ee,evidence,reason,error,false));
        }
        return List.copyOf(result);
    }
    static int integer(JsonNode n,String field) {
        if(!n.path(field).isIntegralNumber()||!n.path(field).canConvertToInt()) throw new IllegalArgumentException("纠错字段 "+field+" 必须为有效整数。");
        return n.path(field).asInt();
    }
    private static String string(JsonNode n,String field) {
        if(!n.path(field).isTextual()||n.path(field).asText().length()>500) throw new IllegalArgumentException("纠错字段 "+field+" 必须为短文本。");
        return n.path(field).asText();
    }
    private static boolean span(String text,int start,int end,String expected) { return text!=null&&start>=0&&end>start&&end<=text.length()&&text.substring(start,end).equals(expected); }
    private static boolean term(String text) { return !text.isBlank()&&text.length()<=30&&text.matches("[\\p{L}\\p{N}_.+#/\\-]+"); }
    private static boolean protectedText(String text) { return text.matches(".*[\\p{N}不没未无否].*")||text.matches("(?i)(no|not|never|cannot|maybe|possibly)")||text.contains("可能")||text.contains("也许")||text.contains("大概"); }
    static List<ImportCorrection> select(List<ImportCorrection> corrections,List<String> acceptedIds) {
        if(acceptedIds==null) return corrections;
        Set<String> selected=new HashSet<>(acceptedIds);
        if(selected.size()!=acceptedIds.size()||!corrections.stream().map(ImportCorrection::id).toList().containsAll(selected)) throw new IllegalArgumentException("纠错建议编号无效。");
        List<ImportCorrection> result=new ArrayList<>();
        for(var c:corrections) {
            boolean accepted=selected.contains(c.id());
            if(accepted && !c.error().isBlank()) throw new IllegalArgumentException("该纠错未通过原词/证据校验，不能采纳。");
            if(accepted && corrections.stream().anyMatch(other->!other.id().equals(c.id())&&selected.contains(other.id())&&other.turnId()==c.turnId()&&other.start()<c.end()&&c.start()<other.end())) throw new IllegalArgumentException("不能同时采纳重叠的纠错建议。");
            result.add(new ImportCorrection(c.id(),c.turnId(),c.start(),c.end(),c.original(),c.replacement(),c.evidenceSource(),c.evidenceTurnId(),c.evidenceStart(),c.evidenceEnd(),c.evidence(),c.reason(),c.error(),accepted));
        }
        return List.copyOf(result);
    }
    static List<ImportTurn> display(List<ImportTurn> turns,List<ImportCorrection> corrections) {
        List<ImportTurn> result=new ArrayList<>(turns);
        for(var t:turns) {
            String value=t.text();
            var changes=corrections.stream().filter(c->c.accepted()&&c.error().isBlank()&&c.turnId()==t.id()).sorted(Comparator.comparingInt(ImportCorrection::start).reversed()).toList();
            for(var c:changes) {
                if(!span(t.text(),c.start(),c.end(),c.original())) throw new IllegalArgumentException("原始转写已变化，请重新识别纠错建议。");
                value=value.substring(0,c.start())+c.replacement()+value.substring(c.end());
            }
            result.set(t.id(),new ImportTurn(t.id(),t.segmentIndex(),t.speakerId(),t.startMs(),t.endMs(),value,t.role(),t.roleCorrected()));
        }
        return result;
    }
}

package com.interviewagent.interview;

import static com.interviewagent.interview.InterviewApi.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import java.util.*;
import java.util.stream.Collectors;

/** Source-based extraction: model assigns roles/references, saved answers remain verbatim. */
final class ImportAnalysis {
    static final int BLOCK_CHARS = 4500;
    record Block(List<Integer> context, List<Integer> added) {}
    static boolean validRole(String role) { return Set.of("INTERVIEWER", "CANDIDATE", "UNKNOWN").contains(role == null ? "" : role); }
    static String line(ImportTurn t) {
        return "[turn="+t.id()+" segment="+t.segmentIndex()+" speaker="+t.segmentIndex()+":"+t.speakerId()+" ms="+t.startMs()+"-"+t.endMs()+" role="+t.role()+" corrected="+t.roleCorrected()+"] "+t.text().replace('\n',' ');
    }
    static List<Block> blocks(List<ImportTurn> turns) {
        List<Block> result = new ArrayList<>(); List<Integer> added = new ArrayList<>(); int size = 0;
        for (ImportTurn t : turns) {
            // ponytail: small transcript scan; use an interval index if import sizes grow beyond the 72k text limit.
            if (turns.stream().anyMatch(other -> other.id()!=t.id() && duplicate(other,t)
                && (other.text().length()>t.text().length() || other.text().length()==t.text().length() && other.id()<t.id()))) continue;
            if (!added.isEmpty() && size + line(t).length() > BLOCK_CHARS) {
                result.add(new Block(List.of(), List.copyOf(added))); added.clear(); size=0;
            }
            added.add(t.id()); size += line(t).length();
        }
        if (!added.isEmpty()) result.add(new Block(List.of(),List.copyOf(added)));
        return result;
    }
    static boolean overlaps(ImportTurn a, ImportTurn b) {
        return a.segmentIndex()!=b.segmentIndex() && a.startMs()!=null && a.endMs()!=null && b.startMs()!=null && b.endMs()!=null
            && a.startMs()<b.endMs() && b.startMs()<a.endMs();
    }
    static boolean duplicate(ImportTurn a, ImportTurn b) {
        String x=a.text().replaceAll("[^\\p{L}\\p{N}]", ""), y=b.text().replaceAll("[^\\p{L}\\p{N}]", "");
        return overlaps(a,b) && !x.isEmpty() && (x.equals(y) || Math.min(x.length(),y.length())>=6 && (x.contains(y)||y.contains(x)));
    }
    static List<Integer> context(List<ImportedQuestion> questions, Block previous, List<ImportTurn> turns) {
        Set<Integer> ids = new TreeSet<>();
        if (!questions.isEmpty()) { var q=questions.getLast(); ids.addAll(q.questionTurnIds()); }
        int chars=0;
        for(int i=previous.added().size()-1;i>=0;i--) {
            int id=previous.added().get(i); int length=line(turns.get(id)).length();
            if(chars+length>1500 && chars>0) break;
            ids.add(id); chars+=length;
        }
        return List.copyOf(ids);
    }
    static String prompt(Block block, List<ImportTurn> turns) {
        return "仅根据原话提取面试官提问和候选人实际回答。候选人反问、面试官讲解、寒暄不可当作候选人回答。错误回答也是回答。不要补写、纠错或猜测经历。speaker是片段局部编号，禁止跨片段声纹匹配，禁止0/1固定映射角色。根据语义及相邻上下文逐发言判断INTERVIEWER/CANDIDATE/UNKNOWN，证据不足UNKNOWN；corrected=true须服从用户。只返回严格JSON：{roles:[{turnId:number,role:string}],questions:[{questionTurnIds:[number],answerTurnIds:[number]}]}。所有编号必须来自输入。问题来源是面试官或待确认；回答来源必须是明确候选人。纯上下文题不要输出，尚未结束的问题有本块新回答则用原问题来源续接。不得将下一题回答挂到上一题。\n只供上下文：\n"
            +block.context().stream().map(i->line(turns.get(i))).collect(Collectors.joining("\n"))+"\n本块新增：\n"
            +block.added().stream().map(i->line(turns.get(i))).collect(Collectors.joining("\n"));
    }
    static void roles(JsonNode root, List<ImportTurn> turns, Set<Integer> allowed) {
        if (!root.path("roles").isArray()) throw new IllegalArgumentException("模型缺少 roles 数组。");
        for (JsonNode r:root.path("roles")) {
            int id=r.path("turnId").asInt(-1); String role=r.path("role").asText("");
            if (!r.path("turnId").isIntegralNumber() || !allowed.contains(id) || !validRole(role)) throw new IllegalArgumentException("模型角色字段无效。");
            ImportTurn old=turns.get(id);
            if (!old.roleCorrected()) turns.set(id,new ImportTurn(id,old.segmentIndex(),old.speakerId(),old.startMs(),old.endMs(),old.text(),role,false));
        }
    }
    static List<Integer> ids(JsonNode node, Set<Integer> allowed) {
        if (!node.isArray()) throw new IllegalArgumentException("模型缺少来源数组。");
        Set<Integer> ids=new TreeSet<>();
        for (JsonNode n:node) { if (!n.isIntegralNumber() || !allowed.contains(n.asInt())) throw new IllegalArgumentException("模型来源编号无效。"); ids.add(n.asInt()); }
        return List.copyOf(ids);
    }
    static List<ImportedQuestion> parse(JsonNode root, Block block, List<ImportTurn> turns) {
        Set<Integer> allowed=new HashSet<>(block.context()); allowed.addAll(block.added());
        roles(root,turns,allowed);
        if (!root.path("questions").isArray() || root.path("questions").size()>80) throw new IllegalArgumentException("模型 questions 数组无效。");
        List<ImportedQuestion> result=new ArrayList<>();
        for (JsonNode item:root.path("questions")) {
            var q=ids(item.path("questionTurnIds"),allowed); var a=ids(item.path("answerTurnIds"),allowed);
            if (q.isEmpty() || q.stream().anyMatch(i->turns.get(i).role().equals("CANDIDATE"))) throw new IllegalArgumentException("问题来源角色无效。");
            if (a.stream().anyMatch(i->i<=q.getLast() || !turns.get(i).role().equals("CANDIDATE"))) throw new IllegalArgumentException("回答必须引用问题之后的候选人原话。");
            if (q.stream().noneMatch(block.added()::contains) && a.stream().noneMatch(block.added()::contains)) continue;
            String question=text(q,turns), answer=text(a,turns);
            if (question.isBlank() || question.length()>4000 || answer.length()>20000) throw new IllegalArgumentException("问答业务字段无效。");
            result.add(new ImportedQuestion(question,answer,result.size()+1,q.stream().anyMatch(i->turns.get(i).role().equals("UNKNOWN"))?"待确认：角色证据不足":"来源："+q+" / "+a,q,a));
        }
        // No answer may jump across an intervening interview question.
        for (var q:result) for (var other:result) if (other!=q && other.questionTurnIds().getFirst()>q.questionTurnIds().getLast()
            && q.answerTurnIds().stream().anyMatch(i->i>=other.questionTurnIds().getFirst())) throw new IllegalArgumentException("回答跨越下一题边界。");
        return result;
    }
    static String text(List<Integer> ids,List<ImportTurn> turns) {
        return text(ids,turns," ");
    }
    static String text(List<Integer> ids,List<ImportTurn> turns,String separator) {
        List<String> parts=new ArrayList<>(); ImportTurn previous=null;
        Set<Integer> kept=new TreeSet<>(ids);
        for(int id:ids) if(ids.stream().anyMatch(other->other!=id && duplicate(turns.get(id),turns.get(other)) &&
            (turns.get(other).text().length()>turns.get(id).text().length() || turns.get(other).text().length()==turns.get(id).text().length() && other<id))) kept.remove(id);
        for(int id:kept) {
            ImportTurn current=turns.get(id); String part=current.text();
            if(previous!=null && overlaps(previous,current)) {
                if(duplicate(previous,current)) {
                    if(part.length()<=previous.text().length()) continue;
                    parts.removeLast();
                } else {
                    // ponytail: only exact suffix/prefix overlap; ambiguous differences remain available for manual verification.
                    for(int n=Math.min(previous.text().length(),part.length());n>=4;n--) if(previous.text().endsWith(part.substring(0,n))) { part=part.substring(n); break; }
                }
            }
            parts.add(part); previous=current;
        }
        return String.join(separator,parts);
    }
    static void merge(List<ImportedQuestion> all,List<ImportedQuestion> next,List<ImportTurn> turns) {
        for(var item:next) {
            int match=-1;
            for(int i=0;i<all.size();i++) { var old=all.get(i); if(old.questionTurnIds().stream().anyMatch(item.questionTurnIds()::contains)
                || old.questionTurnIds().stream().anyMatch(a->item.questionTurnIds().stream().anyMatch(b->duplicate(turns.get(a),turns.get(b))))) { match=i; break; } }
            if(match<0) all.add(item);
            else { var old=all.get(match); Set<Integer> answers=new TreeSet<>(old.answerTurnIds()); answers.addAll(item.answerTurnIds());
                List<Integer> unique=new ArrayList<>(answers);
                all.set(match,new ImportedQuestion(old.question(),text(unique,turns),old.orderIndex(),item.speakerEvidence(),old.questionTurnIds(),List.copyOf(unique)));
            }
        }
    }
    static JsonNode request(ReviewModelClient model,ObjectMapper json,Block block,List<ImportTurn> turns,int timeout,long deadline,int depth) throws Exception {
        for(int attempt=0;attempt<3;attempt++) {
            int remaining=(int)((deadline-System.nanoTime())/1_000_000_000L);
            if(remaining<=0 || Thread.currentThread().isInterrupted()) throw new ReviewFailedException("BUDGET","分析预算用完或处理已中断，有效结果已保留。",null);
            try {
                JsonNode result=model.importJson(prompt(block,turns),Math.min(Math.max(1,timeout),remaining));
                parse(result,block,new ArrayList<>(turns)); return result;
            } catch(ReviewFailedException e) {
                if(e.code().equals("TRUNCATED") && depth<3 && block.added().size()>1) {
                    int mid=block.added().size()/2;
                    Block left=new Block(block.context(),block.added().subList(0,mid));
                    JsonNode first=request(model,json,left,turns,timeout,deadline,depth+1);
                    List<ImportTurn> updated=new ArrayList<>(turns); var questions=parse(first,left,updated);
                    Set<Integer> context=new TreeSet<>(block.context()); context.addAll(context(questions,left,updated));
                    Block right=new Block(List.copyOf(context),block.added().subList(mid,block.added().size()));
                    JsonNode second=request(model,json,right,updated,timeout,deadline,depth+1);
                    var combined=json.createObjectNode(); var qs=combined.putArray("questions"); var rs=combined.putArray("roles");
                    for(var part:List.of(first,second)) { for(JsonNode q:part.path("questions")) qs.add(q); for(JsonNode r:part.path("roles")) rs.add(r); }
                    parse(combined,block,new ArrayList<>(turns)); return combined;
                }
                if(!e.retryable() || attempt==2 || e.code().equals("INVALID_JSON") && attempt==1) throw e;
                Thread.sleep(Math.min(1000L*(attempt+1),Math.max(1,(deadline-System.nanoTime())/1_000_000)));
            } catch(IllegalArgumentException e) { if(attempt>=1) throw e; }
        }
        throw new IllegalStateException("分析重试已耗尽。");
    }
}

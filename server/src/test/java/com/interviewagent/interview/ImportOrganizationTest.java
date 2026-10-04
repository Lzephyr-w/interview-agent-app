package com.interviewagent.interview;

import static com.interviewagent.interview.InterviewApi.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class ImportOrganizationTest {
    final ObjectMapper json=new ObjectMapper();
    ImportTurn turn(int id,String text,String role) { return new ImportTurn(id,0,id%2,null,null,text,role,false); }
    @Test void groupsFollowupsCorrectsWithLinkedEvidenceAndExposesOmissionsWithoutChangingRaw() throws Exception {
        var turns=new ArrayList<>(List.of(
            turn(0,"我做过请求封装项目。","CANDIDATE"),turn(1,"请求怎么封装？","INTERVIEWER"),
            turn(2,"创建Excel实例，设置best run l和timeout。","CANDIDATE"),turn(3,"登录过期呢？","INTERVIEWER"),
            turn(4,"401清除log storage里的token然后登录。500是服务器错误。uploadFile自带断点续传。","CANDIDATE"),
            turn(5,"团队做什么业务？","CANDIDATE"),turn(6,"我们做教育平台。","INTERVIEWER"),
            turn(7,"建议再深入了解项目原理。","INTERVIEWER"),turn(8,"我还做过调度模块。","CANDIDATE"),turn(9,"嗯嗯。","UNKNOWN")));
        var before=turns.stream().map(ImportTurn::text).toList();
        var cards=List.of(new ImportOrganization.Evidence("card","请求封装","Axios、baseURL、localStorage"));
        var block=new ImportAnalysis.Block(List.of(),java.util.stream.IntStream.range(0,turns.size()).boxed().toList());
        var root=json.readTree("""
            {"roles":[],"questions":[
            {"kind":"INTRODUCTION","question":"自我介绍","answer":"做过请求封装项目。","questionTurnIds":[],"answerTurnIds":[0],"notes":[],"edits":[]},
            {"kind":"QA","question":"如何封装请求并处理登录过期与服务错误？","answer":"创建Axios实例，设置baseURL和timeout。\\n\\n401清除localStorage中的token后登录。500是服务器错误。uploadFile自带断点续传。","questionTurnIds":[1,3],"answerTurnIds":[2,4],"notes":[],"edits":[
            {"turnId":2,"original":"Excel","replacement":"Axios","evidenceSource":"EVIDENCE_CARD","evidenceId":"card","evidence":"Axios","reason":"请求封装技术栈","uncertain":false},
            {"turnId":2,"original":"best run l","replacement":"baseURL","evidenceSource":"EVIDENCE_CARD","evidenceId":"card","evidence":"baseURL","reason":"请求配置","uncertain":false},
            {"turnId":4,"original":"log storage","replacement":"localStorage","evidenceSource":"EVIDENCE_CARD","evidenceId":"card","evidence":"localStorage","reason":"登录状态存储","uncertain":false}]},
            {"kind":"CANDIDATE_QUESTION","question":"团队业务是什么？","answer":"教育平台。","questionTurnIds":[5],"answerTurnIds":[6],"notes":[],"edits":[]},
            {"kind":"INTERVIEWER_NOTE","question":"面试官建议","answer":"再深入了解项目原理。","questionTurnIds":[],"answerTurnIds":[7],"notes":[],"edits":[]}]}
            """);
        var topics=ImportOrganization.boundaries(ImportOrganization.parse(root,block,turns,"",cards),turns);
        assertEquals(5,topics.size()); assertTrue(topics.get(1).warnings().isEmpty());
        assertEquals(List.of(1,3),topics.get(1).questionTurnIds()); assertEquals(3,topics.get(1).edits().size());
        assertTrue(topics.get(1).answer().contains("uploadFile自带断点续传")); // Preserve the actual wrong claim.
        assertEquals("CANDIDATE_QUESTION",topics.get(2).kind()); assertTrue(topics.get(2).warnings().isEmpty());
        assertEquals(List.of(8),topics.getLast().questionTurnIds()); assertEquals("UNCOVERED_TURNS",topics.getLast().warnings().getFirst().code());
        assertEquals(before,turns.stream().map(ImportTurn::text).toList());
        assertThrows(IllegalArgumentException.class,()->ImportOrganization.parse(root,block,turns,"",List.of()));
        ((ObjectNode)root.path("questions").get(1).path("edits").get(0)).put("evidenceSource","CONTEXT").put("evidenceId","2").put("evidence","Excel实例");
        var guessed=ImportOrganization.parse(root,block,turns,"",cards).get(1);
        assertTrue(guessed.edits().getFirst().uncertain()); assertEquals("DRAFT_UNCERTAIN",guessed.warnings().getFirst().code());
    }
    @Test void continuationMustKeepOldSourcesAndResolvedOmissionsDisappear() {
        var old=new ImportedQuestion("请求封装？","创建实例。",1,"",List.of(0),List.of(1));
        var gap=new ImportedQuestion("未归入话题的发言","",2,"",List.of(2),List.of(),List.of(),false,"UNASSIGNED:2","UNASSIGNED",List.of(),List.of());
        var result=new ArrayList<>(List.of(old,gap));
        assertThrows(IllegalArgumentException.class,()->ImportOrganization.merge(new ArrayList<>(result),List.of(new ImportedQuestion("请求封装？","后半句。",1,"",List.of(0),List.of(2)))));
        var complete=new ImportedQuestion("请求封装与登录状态？","创建实例并清理登录状态。",1,"",List.of(0),List.of(1,2));
        ImportOrganization.merge(result,List.of(complete)); assertEquals(List.of(complete),result);
        var turns=List.of(turn(0,"请求封装？","UNKNOWN"),turn(1,"创建实例。","UNKNOWN"),turn(2,"继续。","UNKNOWN"));
        var corrected=turns.stream().map(t->new ImportTurn(t.id(),0,0,null,null,t.text(),"INTERVIEWER",true)).toList();
        assertEquals(ImportOrganization.blocks(turns),ImportOrganization.blocks(corrected));
    }
}

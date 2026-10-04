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
    @Test void networkFailuresLeaveAFormatRepairAvailableAndPreserveItsFeedback() throws Exception {
        var turns=List.of(turn(0,"自我介绍？","INTERVIEWER"),turn(1,"我做过前端。","CANDIDATE"),turn(2,"项目怎么实现？","INTERVIEWER"),turn(3,"创建Axios实例。","CANDIDATE"));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1,2,3));
        var valid=(ObjectNode)json.readTree("""
            {"questions":[
            {"kind":"INTRODUCTION","question":"请自我介绍。","answer":"我做过前端。","notes":[],"sourceSpan":{"start":0,"end":1}},
            {"kind":"QA","question":"项目怎么实现？","answer":"创建Axios实例。","notes":[],"sourceSpan":{"start":2,"end":3}}],"omittedSpans":[]}
            """);
        var invalid=valid.deepCopy(); ((ObjectNode)invalid.path("questions").get(1)).put("kind","FOLLOW_UP");
        var model=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(model.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt()))
            .thenThrow(new ReviewFailedException("HTTP_408","上游超时。",null))
            .thenThrow(new ReviewFailedException("CONNECTION","连接失败。",null)).thenReturn(invalid,valid);
        assertEquals(valid,ImportOrganization.request(model,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L));
        var prompts=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(4)).organizeImportJson(prompts.capture(),org.mockito.ArgumentMatchers.anyInt());
        assertTrue(prompts.getAllValues().getLast().contains("第2条问答的kind=“FOLLOW_UP”无效"));
        var interruptedRepair=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(interruptedRepair.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(invalid).thenThrow(new ReviewFailedException("CONNECTION","连接失败。",null)).thenReturn(valid);
        assertEquals(valid,ImportOrganization.request(interruptedRepair,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L));
        var retriedPrompts=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(interruptedRepair,org.mockito.Mockito.times(3)).organizeImportJson(retriedPrompts.capture(),org.mockito.ArgumentMatchers.anyInt());
        assertEquals(retriedPrompts.getAllValues().get(1),retriedPrompts.getAllValues().get(2));
        var jsonRepair=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(jsonRepair.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt()))
            .thenThrow(new ReviewFailedException("HTTP_408","上游超时。",null))
            .thenThrow(new ReviewFailedException("INVALID_JSON","非法JSON。",null)).thenReturn(valid);
        assertEquals(valid,ImportOrganization.request(jsonRepair,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L));
        org.mockito.Mockito.verify(jsonRepair,org.mockito.Mockito.times(3)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
    }
    @Test void formatRepairIsBoundedAndFieldErrorsIdentifyTheQuestionWithoutRelaxingSources() throws Exception {
        var turns=List.of(turn(0,"职业方向？","INTERVIEWER"),turn(1,"前端。","CANDIDATE"));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1));
        var root=(ObjectNode)json.readTree("""
            {"questions":[{"kind":"QA","question":" ","answer":"前端。","notes":[],"sourceSpan":{"start":0,"end":1}}],"omittedSpans":[]}
            """);
        var model=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(model.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(root);
        var error=assertThrows(IllegalArgumentException.class,()->ImportOrganization.request(model,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L));
        assertTrue(error.getMessage().contains("第1条问答的question为空"));
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(2)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
        var item=(ObjectNode)root.path("questions").get(0); item.put("question","职业方向？").put("kind","qa");
        assertEquals("QA",ImportOrganization.parse(root,block,new ArrayList<>(turns),"",List.of()).getFirst().kind());
        item.put("question",42);
        assertTrue(assertThrows(IllegalArgumentException.class,()->ImportOrganization.parse(root,block,new ArrayList<>(turns),"",List.of())).getMessage().contains("第1条问答的question字段必须是文本"));
        item.put("question","职业方向？"); ((ObjectNode)item.path("sourceSpan")).put("end",99);
        assertThrows(IllegalArgumentException.class,()->ImportOrganization.parse(root,block,new ArrayList<>(turns),"",List.of()));
        for(String code:List.of("HTTP_408","AUTHENTICATION")) {
            var failed=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
            org.mockito.Mockito.when(failed.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenThrow(new ReviewFailedException(code,"请求失败。",null));
            assertEquals(code,assertThrows(ReviewFailedException.class,()->ImportOrganization.request(failed,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L)).code());
            org.mockito.Mockito.verify(failed,org.mockito.Mockito.times(code.equals("HTTP_408")?3:1)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
        }
        ((ObjectNode)item.path("sourceSpan")).put("end",1); item.put("question"," ");
        var mixedFormatFailures=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(mixedFormatFailures.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt()))
            .thenThrow(new ReviewFailedException("INVALID_JSON","非法JSON。",null)).thenReturn(root);
        assertThrows(IllegalArgumentException.class,()->ImportOrganization.request(mixedFormatFailures,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L));
        org.mockito.Mockito.verify(mixedFormatFailures,org.mockito.Mockito.times(2)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
        var expired=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        assertEquals("BUDGET",assertThrows(ReviewFailedException.class,()->ImportOrganization.request(expired,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()-1)).code());
        org.mockito.Mockito.verifyNoInteractions(expired);
    }
    @Test void readableSelfIntroductionUsesLinkedNameOnlyWhenTheNameWasSpokenInItsSources() throws Exception {
        String resume="姓名：吴佳童\n广东工业大学，前端开发。";
        assertEquals("吴佳童",ImportOrganization.resumeName(resume,"吴佳彤-软件开发.pdf"));
        assertEquals("吴佳童",ImportOrganization.resumeName("前端开发经历。","吴佳童-软件开发-广东工业大学.pdf"));
        assertEquals("",ImportOrganization.resumeName("","吴佳童-软件开发.pdf"));
        assertEquals("",ImportOrganization.resumeName("姓名：吴佳童\n姓名：张三","吴佳童.pdf"));
        var raw=List.of(turn(0,"请先自我介绍。","INTERVIEWER"),turn(1,"嗯，我叫吴佳彤，做过前端。","CANDIDATE"));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1));
        var root=(ObjectNode)json.readTree("""
            {"questions":[{"kind":"INTRODUCTION","question":"请先做一下自我介绍。","answer":"我叫吴佳彤，做过前端。项目同事叫吴佳彤。","notes":[],"sourceSpan":{"start":0,"end":1}}],"omittedSpans":[]}
            """);
        var turns=new ArrayList<>(raw);
        assertEquals("我叫吴佳童，做过前端。项目同事叫吴佳彤。",ImportOrganization.parse(root,block,turns,resume,List.of()).getFirst().answer());
        assertEquals(raw,turns); assertTrue(root.path("questions").get(0).path("answer").asText().contains("我叫吴佳彤"));
        assertTrue(ImportOrganization.parse(root,block,new ArrayList<>(raw),"",List.of()).getFirst().answer().contains("我叫吴佳彤"));
        var withoutName=new ArrayList<>(List.of(raw.get(0),turn(1,"做过前端。","CANDIDATE")));
        assertTrue(ImportOrganization.parse(root,block,withoutName,resume,List.of()).getFirst().answer().contains("我叫吴佳彤"));
        var manualInterviewer=new ArrayList<>(List.of(raw.get(0),new ImportTurn(1,0,1,null,null,raw.get(1).text(),"INTERVIEWER",true)));
        assertTrue(ImportOrganization.parse(root,block,manualInterviewer,resume,List.of()).getFirst().answer().contains("我叫吴佳彤"));
        var item=(ObjectNode)root.path("questions").get(0);
        item.put("kind","QA").put("answer","我的名字是吴佳彤，是前端开发。价格是300元。");
        assertEquals("我的名字是吴佳童，是前端开发。价格是300元。",ImportOrganization.parse(root,block,new ArrayList<>(raw),resume,List.of()).getFirst().answer());
        item.put("kind","CANDIDATE_QUESTION");
        assertTrue(ImportOrganization.parse(root,block,new ArrayList<>(raw),resume,List.of()).getFirst().answer().contains("吴佳彤"));
        item.put("kind","INTRODUCTION").put("answer","我叫李明，做过前端。");
        assertEquals("我叫李明，做过前端。",ImportOrganization.parse(root,block,new ArrayList<>(raw),resume,List.of()).getFirst().answer());
        assertTrue(ImportOrganization.isOrganized("readable-qa-v5"));
    }
    @Test void readableRolesUseLocalSpeakersAndRangesWithoutOverwritingManualRolesOrRejectingProse() throws Exception {
        var raw=List.of(
            new ImportTurn(0,0,0,0L,1000L,"你先坐。","UNKNOWN",false),
            new ImportTurn(1,0,1,1000L,2000L,"这是我的简历。","UNKNOWN",false),
            new ImportTurn(2,1,0,2000L,3000L,"我做了请求封装。","UNKNOWN",false),
            new ImportTurn(3,1,1,3000L,4000L,"怎么实现？","UNKNOWN",false),
            new ImportTurn(4,1,0,4000L,5000L,"面试官插话。","UNKNOWN",false),
            new ImportTurn(5,0,0,5000L,6000L,"用户确认是候选人。","CANDIDATE",true),
            new ImportTurn(6,-1,null,null,null,"文本回答。","UNKNOWN",false),
            new ImportTurn(7,1,2,6000L,7000L,"转写不清。","UNKNOWN",false));
        var block=new ImportAnalysis.Block(List.of(),java.util.stream.IntStream.range(0,raw.size()).boxed().toList());
        var root=(ObjectNode)json.readTree("""
            {"questions":[{"kind":"QA","question":"项目怎么实现？","answer":"创建Axios实例。","notes":[],"sourceSpan":{"start":0,"end":7}}],"omittedSpans":[],
             "speakerRoles":[{"segmentIndex":0,"speakerId":0,"role":"INTERVIEWER"},{"segmentIndex":0,"speakerId":1,"role":"CANDIDATE"},
                             {"segmentIndex":1,"speakerId":0,"role":"CANDIDATE"},{"segmentIndex":1,"speakerId":1,"role":"INTERVIEWER"}],
             "roleSpans":[{"start":4,"end":4,"role":"INTERVIEWER"},{"start":6,"end":6,"role":"CANDIDATE"}]}
            """);
        var turns=new ArrayList<>(raw);
        var drafts=ImportOrganization.parse(root,block,turns,"",List.of());
        assertEquals(List.of("INTERVIEWER","CANDIDATE","CANDIDATE","INTERVIEWER","INTERVIEWER","CANDIDATE","CANDIDATE","UNKNOWN"),turns.stream().map(ImportTurn::role).toList());
        assertEquals(raw.get(5),turns.get(5)); // Human corrections take priority over every automatic mapping.
        for(int i=0;i<raw.size();i++) { assertEquals(raw.get(i).text(),turns.get(i).text()); assertEquals(raw.get(i).startMs(),turns.get(i).startMs()); assertEquals(raw.get(i).speakerId(),turns.get(i).speakerId()); }
        assertEquals("创建Axios实例。",drafts.getFirst().answer());
        assertTrue(drafts.getFirst().questionTurnIds().isEmpty()); assertTrue(drafts.getFirst().warnings().isEmpty());
        var conflicting=root.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)conflicting.path("speakerRoles")).addObject().put("segmentIndex",0).put("speakerId",0).put("role","CANDIDATE");
        ((com.fasterxml.jackson.databind.node.ArrayNode)conflicting.path("roleSpans")).addObject().put("start",4).put("end",4).put("role","CANDIDATE");
        var disputed=new ArrayList<>(raw); ImportOrganization.parse(conflicting,block,disputed,"",List.of());
        assertEquals("UNKNOWN",disputed.get(0).role()); assertEquals("UNKNOWN",disputed.get(4).role()); assertEquals(raw.get(5),disputed.get(5));
        var scoped=root.deepCopy(); ((ObjectNode)scoped.path("questions").get(0).path("sourceSpan")).put("end",1);
        scoped.putArray("speakerRoles").addObject().put("segmentIndex",0).put("speakerId",1).put("role","CANDIDATE");
        var invalid=scoped.putArray("roleSpans"); invalid.addObject().put("start",2).put("end",3).put("role","INTERVIEWER"); invalid.addObject().put("start",0).put("end",0).put("role","面试官");
        var isolated=new ArrayList<>(raw); ImportOrganization.parse(scoped,new ImportAnalysis.Block(List.of(),List.of(0,1)),isolated,"",List.of());
        assertEquals("UNKNOWN",isolated.get(0).role()); assertEquals("CANDIDATE",isolated.get(1).role()); assertEquals(raw.subList(2,8),isolated.subList(2,8));
        var model=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        scoped.put("speakerRoles","invalid"); invalid.removeAll().addObject().put("start",0).put("end",99).put("role","INTERVIEWER");
        org.mockito.Mockito.when(model.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(scoped);
        assertEquals(scoped,ImportOrganization.request(model,json,new ImportAnalysis.Block(List.of(),List.of(0,1)),raw,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L));
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
        assertTrue(ImportOrganization.isOrganized("readable-qa-v4"));
    }
    @Test void readableDraftKeepsEditedProseWithoutPerWordMetadataAndValidatesCompactSources() throws Exception {
        var turns=new ArrayList<>(List.of(turn(0,"这是我的简历。","UNKNOWN"),turn(1,"请介绍自己。","INTERVIEWER"),turn(2,"嗯我叫吴佳彤，做过前端。","CANDIDATE"),turn(3,"请求怎么封装？","INTERVIEWER"),turn(4,"创建Excel实例，清掉log storage的token。","CANDIDATE"),turn(5,"还用过uploadFile，它能断点续传。","CANDIDATE"),turn(6,"忘记具体原理。","CANDIDATE")));
        var raw=List.copyOf(turns); var block=new ImportAnalysis.Block(List.of(),List.of(0,1,2,3,4,5,6));
        var root=json.readTree("""
            {"questions":[
            {"kind":"INTRODUCTION","question":"请先做一下自我介绍。","answer":"我叫吴佳童，做过前端开发。","notes":[],"sourceSpan":{"start":1,"end":2}},
            {"kind":"QA","question":"请求如何封装？","answer":"创建Axios实例。\\n\\n清除localStorage中的token。uploadFile能断点续传。","notes":["技术词按上下文整理，保留当时对断点续传的判断。"],"sourceSpan":{"start":3,"end":5}}],
            "omittedSpans":[{"start":0,"end":0,"reason":"SOCIAL"}]}
            """);
        var topics=ImportOrganization.boundaries(ImportOrganization.parse(root,block,turns,"吴佳童",List.of()),turns);
        assertEquals(3,topics.size()); assertEquals("我叫吴佳童，做过前端开发。",topics.getFirst().answer());
        var qa=topics.get(1); assertEquals("请求如何封装？",qa.question()); assertTrue(qa.answer().contains("Axios")); assertTrue(qa.answer().contains("\n\n"));
        assertTrue(qa.answer().contains("uploadFile能断点续传")); assertTrue(qa.edits().isEmpty()); assertTrue(qa.questionTurnIds().isEmpty()); assertTrue(qa.answerTurnIds().isEmpty());
        assertEquals(new ImportSourceSpan(3,5),qa.sourceSpan()); assertEquals("UNASSIGNED",topics.getLast().kind()); assertEquals(new ImportSourceSpan(6,6),topics.getLast().sourceSpan());
        assertEquals(raw,turns); assertFalse(json.valueToTree(qa).has("questionTurnIds")); assertFalse(json.valueToTree(qa).has("answerTurnIds"));
        assertTrue(qa.warnings().getFirst().turnIds().isEmpty());
        var source=(ObjectNode)root.path("questions").get(1).path("sourceSpan"); source.put("end",99);
        assertThrows(IllegalArgumentException.class,()->ImportOrganization.parse(root,block,turns,"",List.of()));
        source.put("end",5);
        assertThrows(IllegalArgumentException.class,()->ImportOrganization.parse(root,new ImportAnalysis.Block(List.of(1,2),List.of(4,5,6)),turns,"",List.of()));
        ((ObjectNode)root.path("omittedSpans").get(0)).put("reason","TECHNICAL");
        assertThrows(IllegalArgumentException.class,()->ImportOrganization.parse(root,block,turns,"",List.of()));
        var previous=new ArrayList<>(List.of(topics.getFirst()));
        var incomplete=new ImportedQuestion("介绍补充？","前端开发。",1,"",List.of(),List.of(),List.of(),false,"intro-later","INTRODUCTION",List.of(),List.of(),new ImportSourceSpan(2,3));
        ImportOrganization.merge(previous,List.of(incomplete)); assertEquals(2,previous.size());
        assertEquals(topics.getFirst(),previous.getFirst()); assertTrue(ImportOrganization.boundaries(previous,turns).getFirst().warnings().stream().anyMatch(w->w.code().equals("SOURCE_OVERLAP")));
        assertTrue(ImportOrganization.isOrganized("topic-editor-v2")); // Saved drafts still use the legacy fields without being rewritten.
        assertTrue(ImportOrganization.isOrganized("readable-qa-v3"));
    }
    @Test void sourceReconciliationExtendsAnAlreadyEditedIntroductionAndKeepsRealOmissions() throws Exception {
        var turns=List.of(turn(0,"请介绍自己。","UNKNOWN"),turn(1,"我有两段实习。","CANDIDATE"),turn(2,"负责需求评审开发联调。","CANDIDATE"),turn(3,"积累了团队协作和B端C端及跨端经验。","CANDIDATE"),turn(4,"用靠带做面试项目，以上是自我介绍。","CANDIDATE"),turn(5,"职业方向？","INTERVIEWER"),turn(6,"前端开发。","CANDIDATE"),turn(7,"忘了具体原理。","CANDIDATE"));
        var block=new ImportAnalysis.Block(List.of(),java.util.stream.IntStream.range(0,turns.size()).boxed().toList());
        var root=json.readTree("""
            {"questions":[
            {"kind":"INTRODUCTION","question":"请做自我介绍。","answer":"我有两段实习，负责需求评审、开发和联调。\\n\\n积累了团队协作、B/C端和跨端经验，用Codex做面试项目。","notes":[],"sourceSpan":{"start":0,"end":2}},
            {"kind":"QA","question":"职业方向？","answer":"前端开发。","notes":[],"sourceSpan":{"start":5,"end":6}}],"omittedSpans":[],"speakerRoles":[],"roleSpans":[{"start":0,"end":0,"role":"INTERVIEWER"}]}
            """);
        var patch=json.readTree("""
            {"sourceSpans":[{"questionIndex":0,"sourceSpan":{"start":0,"end":4}}]}
            """);
        var model=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(model.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(root,patch);
        var reply=ImportOrganization.request(model,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L);
        assertEquals("UNKNOWN",turns.getFirst().role()); // Validation and source repair must not mutate the caller's turns.
        var restoredTurns=new ArrayList<>(turns); var topics=ImportOrganization.parse(reply,block,restoredTurns,"",List.of());
        assertEquals("INTERVIEWER",restoredTurns.getFirst().role());
        assertEquals(3,topics.size()); assertEquals(new ImportSourceSpan(0,4),topics.getFirst().sourceSpan());
        assertEquals(1L,topics.stream().filter(q->q.kind().equals("INTRODUCTION")).count());
        assertEquals(root.path("questions").get(0).path("answer").asText(),topics.getFirst().answer());
        assertEquals(root.path("questions").get(1),reply.path("questions").get(1));
        assertEquals(root.path("roleSpans"),reply.path("roleSpans"));
        assertEquals("UNASSIGNED",topics.getLast().kind()); assertEquals(new ImportSourceSpan(7,7),topics.getLast().sourceSpan());
        assertEquals(2,root.path("questions").get(0).path("sourceSpan").path("end").asInt()); // Never mutate the first valid response or raw turns.
        var prompts=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(2)).organizeImportJson(prompts.capture(),org.mockito.ArgumentMatchers.anyInt());
        assertTrue(prompts.getAllValues().getLast().contains("不能生成第二条介绍"));
        assertTrue(prompts.getAllValues().getLast().contains("忘了具体原理"));
        assertEquals("用靠带做面试项目，以上是自我介绍。",turns.get(4).text());
        var complete=(ObjectNode)reply.deepCopy();
        var fullAnswer=(ObjectNode)complete.path("questions").get(1);
        fullAnswer.put("answer","前端开发，具体原理记不清了。"); ((ObjectNode)fullAnswer.path("sourceSpan")).put("end",7);
        var noRepair=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(noRepair.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(complete);
        assertEquals(complete,ImportOrganization.request(noRepair,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L));
        org.mockito.Mockito.verify(noRepair,org.mockito.Mockito.times(1)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
    }
    @Test void invalidOrFailedSourceRepairKeepsTheValidDraftAndUnassociatedSourcesWithoutRetrying() throws Exception {
        var turns=List.of(turn(0,"请介绍自己。","INTERVIEWER"),turn(1,"我做过项目。","CANDIDATE"),turn(2,"团队协作经验。","CANDIDATE"),turn(3,"职业方向？","INTERVIEWER"),turn(4,"前端。","CANDIDATE"));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1,2,3,4));
        var root=json.readTree("""
            {"questions":[
            {"kind":"INTRODUCTION","question":"介绍自己。","answer":"我做过项目，有团队协作经验。","notes":[],"sourceSpan":{"start":0,"end":1}},
            {"kind":"QA","question":"职业方向？","answer":"前端。","notes":[],"sourceSpan":{"start":3,"end":4}}],"omittedSpans":[]}
            """);
        for(String patch:List.of("{\"questionIndex\":0,\"sourceSpan\":{\"start\":1,\"end\":2}}","{\"questionIndex\":0,\"sourceSpan\":{\"start\":0,\"end\":99}}","{\"questionIndex\":0,\"sourceSpan\":{\"start\":0,\"end\":4}}","{\"questionIndex\":99,\"sourceSpan\":{\"start\":0,\"end\":2}}","{\"questionIndex\":0,\"sourceSpan\":{\"start\":0,\"end\":2}},{\"questionIndex\":1,\"sourceSpan\":{\"start\":2,\"end\":4}}")) {
            var model=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
            org.mockito.Mockito.when(model.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(root,json.readTree("{\"sourceSpans\":["+patch+"]}"));
            var reply=ImportOrganization.request(model,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L);
            assertEquals(root,reply); assertEquals("UNASSIGNED",ImportOrganization.parse(reply,block,turns,"",List.of()).getLast().kind());
            org.mockito.Mockito.verify(model,org.mockito.Mockito.times(2)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
        }
        var timeout=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(timeout.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(root).thenThrow(new ReviewFailedException("HTTP_TIMEOUT","核对请求超时。",null));
        assertEquals(root,ImportOrganization.request(timeout,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+60_000_000_000L));
        org.mockito.Mockito.verify(timeout,org.mockito.Mockito.times(2)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
    }
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
        var rejected=ImportOrganization.parse(root,block,turns,"",List.of()).get(1);
        assertTrue(rejected.edits().isEmpty()); assertTrue(rejected.answer().contains("Excel实例"));
        assertTrue(rejected.warnings().stream().anyMatch(w->w.code().equals("EDIT_VALIDATION_FAILED")));
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
    @Test void overlappingTopicsInOneReplyStaySeparateAndDoNotTriggerContinuationRetries() throws Exception {
        var turns=new ArrayList<>(List.of(turn(0,"请求封装和登录过期怎么处理？","INTERVIEWER"),turn(1,"创建实例。","CANDIDATE"),turn(2,"清理token。","CANDIDATE"),turn(3,"跳回登录页。","CANDIDATE")));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1,2));
        var root=json.readTree("""
            {"roles":[],"questions":[
            {"kind":"QA","question":"请求如何封装？","answer":"创建实例。","questionTurnIds":[0],"answerTurnIds":[1],"notes":[],"edits":[]},
            {"kind":"QA","question":"登录过期如何处理？","answer":"清理token。","questionTurnIds":[0],"answerTurnIds":[2],"notes":[],"edits":[]}]}
            """);
        var model=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(model.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(root);
        var reply=ImportOrganization.request(model,json,block,turns,"",List.of(),List.of(),10,System.nanoTime()+10_000_000_000L);
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
        var topics=new ArrayList<ImportedQuestion>();
        ImportOrganization.merge(topics,ImportOrganization.parse(reply,block,turns,"",List.of()));
        assertEquals(List.of("创建实例。","清理token。"),topics.stream().map(ImportedQuestion::answer).toList());
        var preview=ImportOrganization.boundaries(topics,turns);
        assertNotEquals(preview.getFirst().sourceId(),preview.getLast().sourceId());
        for(var topic:preview) {
            assertFalse(topic.reviewConfirmed()); assertEquals(1,topic.warnings().size());
            assertEquals("SOURCE_OVERLAP",topic.warnings().getFirst().code()); assertEquals(List.of(0),topic.warnings().getFirst().turnIds());
        }
        assertEquals(preview,ImportOrganization.boundaries(preview,turns));
        assertTrue(ImportOrganization.boundaries(List.of(preview.getFirst()),turns).getFirst().warnings().isEmpty());
        var continued=new ImportedQuestion("请求封装与登录过期？","创建实例并清理token。",1,"",List.of(0),List.of(1,2));
        var sameReply=new ImportedQuestion("登录跳转？","跳回登录页。",2,"",List.of(0),List.of(3));
        var previous=new ArrayList<>(List.of(topics.getFirst()));
        ImportOrganization.merge(previous,List.of(continued,sameReply));
        assertEquals(List.of(continued,sameReply),previous); // Preserve both replies after updating an earlier block once.
        var ambiguous=new ArrayList<>(topics);
        var later=new ImportedQuestion("登录处理补充？","清理token并跳回登录页。",1,"",List.of(0),List.of(2,3));
        ImportOrganization.merge(ambiguous,List.of(later));
        assertEquals(List.of(topics.getFirst(),topics.getLast(),later),ambiguous); // Shared old sources cannot identify one continuation safely.
    }
    @Test void editMetadataMismatchDoesNotDiscardOtherTopicsOrRetryTheWholeModelReply() throws Exception {
        var turns=new ArrayList<>(List.of(turn(0,"请求怎么封装？","INTERVIEWER"),turn(1,"创建Excel实例。","CANDIDATE"),turn(2,"认证怎么做？","INTERVIEWER"),turn(3,"请求头带token。","CANDIDATE")));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1,2,3));
        var root=json.readTree("""
            {"roles":[],"questions":[
            {"kind":"QA","question":"请求封装？","answer":"创建Axios实例。","questionTurnIds":[0],"answerTurnIds":[1],"notes":[],"edits":[{"turnId":0,"original":"Excel","replacement":"Axios","evidenceSource":"RESUME","evidenceId":"","evidence":"Axios","reason":"名称核对","uncertain":false}]},
            {"kind":"QA","question":"认证？","answer":"请求头带token。","questionTurnIds":[2],"answerTurnIds":[3],"notes":[],"edits":[]}]}
            """);
        var edit=(ObjectNode)root.path("questions").get(0).path("edits").get(0);
        var fixed=ImportOrganization.parse(root,block,turns,"Axios",List.of());
        assertEquals(1,fixed.getFirst().edits().getFirst().turnId()); // Correct only an exact, unique source within this topic.
        edit.put("original","不存在的原词");
        var model=org.mockito.Mockito.mock(com.interviewagent.ai.ReviewModelClient.class);
        org.mockito.Mockito.when(model.organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(root);
        var reply=ImportOrganization.request(model,json,block,turns,"Axios",List.of(),List.of(),10,System.nanoTime()+10_000_000_000L);
        org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).organizeImportJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
        var topics=ImportOrganization.boundaries(ImportOrganization.parse(reply,block,turns,"Axios",List.of()),turns);
        assertEquals(2,topics.size()); assertEquals("创建Excel实例。",topics.getFirst().answer()); assertTrue(topics.getFirst().edits().isEmpty());
        assertEquals("EDIT_VALIDATION_FAILED",topics.getFirst().warnings().getFirst().code()); assertEquals("请求头带token。",topics.getLast().answer());
        assertTrue(topics.getLast().warnings().isEmpty());
        edit.put("replacement","未用于最终稿的词");
        var unused=ImportOrganization.parse(root,block,turns,"Axios",List.of()).getFirst();
        assertEquals("创建Axios实例。",unused.answer()); assertTrue(unused.edits().isEmpty());
        assertTrue(unused.warnings().stream().anyMatch(w->w.code().equals("EDIT_VALIDATION_FAILED")));
        ((ObjectNode)root.path("questions").get(0)).putArray("answerTurnIds").add(99);
        assertThrows(IllegalArgumentException.class,()->ImportOrganization.parse(root,block,turns,"Axios",List.of()));
    }
}

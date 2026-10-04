package com.interviewagent.interview;

import static com.interviewagent.interview.InterviewApi.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImportAnalysisTest {
    final ObjectMapper json=new ObjectMapper();
    ImportTurn turn(int id,int segment,int speaker,String text,String role) { return new ImportTurn(id,segment,speaker,(long)id*1000,(long)(id+1)*1000,text,role,false); }
    @Test void inferredRolesDoNotMoveCachedBlocksOrContextBoundaries() throws Exception {
        var first=turn(0,0,0,"问题？","UNKNOWN");
        var empty=turn(1,0,1,"","UNKNOWN");
        var answer=turn(1,0,1,"答".repeat(ImportAnalysis.BLOCK_CHARS-ImportAnalysis.line(empty).length()-ImportAnalysis.line(first).length()),"UNKNOWN");
        var original=List.of(first,answer,turn(2,0,1,"继续回答。","UNKNOWN"));
        var classified=original.stream().map(t->new ImportTurn(t.id(),t.segmentIndex(),t.speakerId(),t.startMs(),t.endMs(),t.text(),t.id()==0?"INTERVIEWER":"CANDIDATE",true)).toList();
        assertEquals(ImportAnalysis.blocks(original),ImportAnalysis.blocks(classified));
        var cached=json.readTree("{\"roles\":[{\"turnId\":0,\"role\":\"INTERVIEWER\"},{\"turnId\":1,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}");
        assertDoesNotThrow(()->ImportAnalysis.parse(cached,ImportAnalysis.blocks(classified).getFirst(),new ArrayList<>(classified)));
        var tail=turn(2,0,1,"尾句。","UNKNOWN");
        var context=List.of(turn(0,0,0,"开头。","UNKNOWN"),turn(1,0,1,"答".repeat(1500-ImportAnalysis.line(turn(1,0,1,"","UNKNOWN")).length()-ImportAnalysis.line(tail).length()),"UNKNOWN"),tail);
        var classifiedContext=context.stream().map(t->new ImportTurn(t.id(),t.segmentIndex(),t.speakerId(),t.startMs(),t.endMs(),t.text(),"INTERVIEWER",true)).toList();
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1,2));
        assertEquals(ImportAnalysis.context(List.of(),block,context),ImportAnalysis.context(List.of(),block,classifiedContext));
    }
    @Test void roleValidationReportsTheBadFieldAndRetriesWithFeedback() throws Exception {
        var turns=List.of(turn(0,0,0,"问题？","UNKNOWN"),turn(1,0,1,"回答。","UNKNOWN"));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1));
        var model=mock(ReviewModelClient.class);
        var invalid=json.readTree("{\"roles\":[{\"turnId\":99,\"role\":\"INTERVIEWER\"}],\"questions\":[]}");
        var valid=json.readTree("{\"roles\":[{\"turnId\":0,\"role\":\"INTERVIEWER\"},{\"turnId\":1,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}");
        when(model.importJson(anyString(),anyInt())).thenReturn(invalid).thenReturn(valid);
        assertEquals(valid,ImportAnalysis.request(model,json,block,turns,1,System.nanoTime()+10_000_000_000L,0));
        var prompts=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model,times(2)).importJson(prompts.capture(),anyInt());
        assertTrue(prompts.getAllValues().getLast().contains("上次返回未通过校验"));
        assertTrue(prompts.getAllValues().getLast().contains("99"));
        assertEquals("UNKNOWN",turns.getFirst().role());
        reset(model); when(model.importJson(anyString(),anyInt())).thenReturn(invalid);
        assertThrows(IllegalArgumentException.class,()->ImportAnalysis.request(model,json,block,turns,1,System.nanoTime()+10_000_000_000L,0));
        verify(model,times(2)).importJson(anyString(),anyInt());
        for(String role:List.of("{\"turnId\":\"0\",\"role\":\"INTERVIEWER\"}","{\"turnId\":4294967296,\"role\":\"INTERVIEWER\"}","{\"turnId\":0,\"role\":\"interviewer\"}")) {
            var response=json.readTree("{\"roles\":["+role+"],\"questions\":[]}");
            assertThrows(IllegalArgumentException.class,()->ImportAnalysis.parse(response,block,new ArrayList<>(turns)));
        }
    }
    @Test void continuesQuestionBySourceAndSeparatesCandidateQuestionsAndInterviewerExplanation() throws Exception {
        List<ImportTurn> turns=new ArrayList<>(List.of(turn(0,0,0,"如何缓存？","INTERVIEWER"),turn(1,0,1,"先查本地。","CANDIDATE"),
            turn(2,1,0,"再查远端。","CANDIDATE"),turn(3,1,0,"团队有多少人？","CANDIDATE"),turn(4,1,1,"我们有五个人。","INTERVIEWER")));
        List<ImportedQuestion> all=new ArrayList<>();
        var first=json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}");
        ImportAnalysis.merge(all,ImportAnalysis.parse(first,new ImportAnalysis.Block(List.of(),List.of(0,1)),turns),turns);
        var second=json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1,2]}]}");
        ImportAnalysis.merge(all,ImportAnalysis.parse(second,new ImportAnalysis.Block(List.of(0,1),List.of(2,3,4)),turns),turns);
        assertEquals(1,all.size()); assertEquals("先查本地。 再查远端。",all.getFirst().answer());
        assertEquals(0,turns.get(0).speakerId()); assertEquals(0,turns.get(2).speakerId()); assertNotEquals(turns.get(0).role(),turns.get(2).role());
        var uncertain=ImportAnalysis.parse(json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[3],\"answerTurnIds\":[4]}]}"),new ImportAnalysis.Block(List.of(),List.of(3,4)),turns).getFirst();
        assertTrue(uncertain.warnings().stream().anyMatch(w->w.code().equals("QUESTION_ROLE_UNCERTAIN")));
        assertTrue(uncertain.warnings().stream().anyMatch(w->w.code().equals("ANSWER_ROLE_CONFLICT")));
        assertTrue(ImportAnalysis.prompt(new ImportAnalysis.Block(List.of(0),List.of(2)),turns).contains("speaker=1:0"));
    }
    @Test void joinsAsrTurnsWithoutArtificialLinesAndPreservesOriginalParagraphsAndEdits() {
        var turns=List.of(turn(0,0,1,"我主要做的是。","CANDIDATE"),turn(1,0,1,"企业宣传片。","CANDIDATE"),turn(2,1,0,"Node.js 和 React。\n第二段原文。","CANDIDATE"));
        var ids=List.of(0,1,2);
        String compact="我主要做的是。 企业宣传片。 Node.js 和 React。\n第二段原文。";
        assertEquals(compact,ImportAnalysis.text(ids,turns));
        assertEquals(compact,InterviewImportService.compactGeneratedText("我主要做的是。\n企业宣传片。\nNode.js 和 React。\n第二段原文。",ids,turns));
        String edited="我主要做企业宣传片。\n\n这是手工补充的段落。";
        assertEquals(edited,InterviewImportService.compactGeneratedText(edited,ids,turns));
        assertEquals(edited,InterviewImportService.compactGeneratedText(edited,List.of(),turns));
        assertEquals(edited,InterviewImportService.compactGeneratedText(edited,List.of(99),turns));
        assertEquals("Node.js 和 React。\n第二段原文。",turns.get(2).text());
    }
    @Test void keepsBoundaryConflictForReviewAndHonorsCorrectedRole() throws Exception {
        var turns=new ArrayList<>(List.of(turn(0,0,0,"第一题？","INTERVIEWER"),turn(1,0,0,"第二题？","INTERVIEWER"),turn(2,0,1,"第二题回答","CANDIDATE")));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1,2));
        var questions=ImportAnalysis.parse(json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[2]},{\"questionTurnIds\":[1],\"answerTurnIds\":[2]}]}"),block,turns);
        assertEquals("QUESTION_BOUNDARY_CONFLICT",questions.getFirst().warnings().getFirst().code());
        assertEquals(1,ImportAnalysis.boundaries(questions).getFirst().warnings().size());
        var t=turns.get(2); turns.set(2,new ImportTurn(2,0,1,2000L,3000L,t.text(),"INTERVIEWER",true));
        var corrected=ImportAnalysis.parse(json.readTree("{\"roles\":[{\"turnId\":2,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[1],\"answerTurnIds\":[2]}]}"),block,turns).getFirst();
        assertEquals("ANSWER_ROLE_CONFLICT",corrected.warnings().getFirst().code());
        assertEquals("INTERVIEWER",turns.get(2).role());
    }
    @Test void preservesOrderConflictsButStillRejectsInventedOrOverflowingSourceIds() throws Exception {
        var turns=new ArrayList<>(List.of(turn(0,0,0,"回答。","UNKNOWN"),turn(1,0,1,"问题？","INTERVIEWER")));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1));
        var result=ImportAnalysis.parse(json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[1],\"answerTurnIds\":[0]}]}"),block,turns).getFirst();
        assertEquals("回答。",result.answer());
        assertEquals(List.of("ANSWER_ORDER_CONFLICT","ANSWER_ROLE_UNCERTAIN"),result.warnings().stream().map(ImportWarning::code).toList());
        assertEquals("UNKNOWN",turns.getFirst().role());
        for(String id:List.of("99","4294967296","\"0\"")) assertThrows(IllegalArgumentException.class,()->ImportAnalysis.parse(json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[1],\"answerTurnIds\":["+id+"]}]}"),block,turns));
    }
    @Test void correctionUsesExactOccurrenceAndEvidenceWithoutChangingRawOrWrongTechnicalAnswer() throws Exception {
        var turns=List.of(turn(0,0,1,"靠带靠带。我不知道，uploadFile 就是分片上传。","CANDIDATE"));
        var block=new ImportAnalysis.Block(List.of(),List.of(0));
        String suggestion="{\"turnId\":0,\"start\":2,\"end\":4,\"original\":\"靠带\",\"replacement\":\"Codex\",\"evidenceSource\":\"RESUME\",\"evidenceStart\":0,\"evidenceEnd\":5,\"evidence\":\"Codex\",\"reason\":\"本场简历名称\"}";
        var corrections=ImportCorrections.parse(json.readTree("{\"corrections\":["+suggestion+"]}"),block,turns,"Codex");
        assertEquals("",corrections.getFirst().error());
        assertEquals(turns,ImportCorrections.display(turns,corrections));
        var accepted=ImportCorrections.select(corrections,List.of(corrections.getFirst().id()));
        assertEquals("靠带Codex。我不知道，uploadFile 就是分片上传。",ImportCorrections.display(turns,accepted).getFirst().text());
        assertEquals(turns,ImportCorrections.display(turns,ImportCorrections.select(accepted,List.of())));
        assertEquals("靠带靠带。我不知道，uploadFile 就是分片上传。",turns.getFirst().text());
        for(String invalid:List.of(suggestion.replace("\"start\":2","\"start\":1"),suggestion.replace("\"evidenceStart\":0","\"evidenceStart\":1"),suggestion.replace("Codex","C2"))) {
            var rejected=ImportCorrections.parse(json.readTree("{\"corrections\":["+invalid+"]}"),block,turns,"Codex");
            assertFalse(rejected.getFirst().error().isBlank());
            assertThrows(IllegalArgumentException.class,()->ImportCorrections.select(rejected,List.of(rejected.getFirst().id())));
        }
        var overlap=ImportCorrections.parse(json.readTree("{\"corrections\":["+suggestion+","+suggestion.replace("\"start\":2,\"end\":4,\"original\":\"靠带\"","\"start\":2,\"end\":3,\"original\":\"靠\"")+"]}"),block,turns,"Codex");
        assertThrows(IllegalArgumentException.class,()->ImportCorrections.select(overlap,overlap.stream().map(ImportCorrection::id).toList()));
    }
    @Test void transientRetriesAreBoundedAndAuthNeverRetries() throws Exception {
        var model=mock(ReviewModelClient.class); var block=new ImportAnalysis.Block(List.of(),List.of(0)); var turns=List.of(turn(0,0,0,"问题？","UNKNOWN"));
        for(String code:List.of("HTTP_408","HTTP_429")) {
            when(model.importJson(anyString(),anyInt())).thenThrow(new ReviewFailedException(code,"busy",null));
            assertThrows(ReviewFailedException.class,()->ImportAnalysis.request(model,json,block,turns,1,System.nanoTime()+10_000_000_000L,0));
            verify(model,times(3)).importJson(anyString(),anyInt()); reset(model);
        }
        when(model.importJson(anyString(),anyInt())).thenThrow(new ReviewFailedException("AUTHENTICATION","auth",null));
        assertThrows(ReviewFailedException.class,()->ImportAnalysis.request(model,json,block,turns,1,System.nanoTime()+10_000_000_000L,0));
        verify(model,times(1)).importJson(anyString(),anyInt());
    }
    @Test void overlapUsesTimeAndRawTranscriptRemainsAvailable() {
        var a=new ImportTurn(0,0,0,118000L,120000L,"使用React管理状态", "CANDIDATE",false);
        var b=new ImportTurn(1,1,1,118500L,121000L,"使用React管理状态并测试", "CANDIDATE",false);
        assertTrue(ImportAnalysis.duplicate(a,b));
        assertEquals(List.of(1),ImportAnalysis.blocks(List.of(a,b)).getFirst().added());
        assertEquals("使用React管理状态并测试",ImportAnalysis.text(List.of(0,1),List.of(a,b)));
        var raw=new ArrayList<ImportTurn>();
        InterviewImportService.appendTurns(raw,new com.interviewagent.ai.storage.AudioTranscriptionService.Transcript("raw",List.of(new com.interviewagent.ai.storage.AudioTranscriptionService.Turn(1,1,118500L,121000L,b.text()),new com.interviewagent.ai.storage.AudioTranscriptionService.Turn(0,0,118000L,120000L,a.text()))));
        assertEquals(2,raw.size()); assertEquals(a.text(),raw.getFirst().text()); assertEquals(0,raw.getFirst().id());
        var later=new ImportTurn(2,2,0,250000L,251000L,a.text(),"CANDIDATE",false); assertFalse(ImportAnalysis.duplicate(a,later));
    }
    @Test void truncationSplitsTurnsAndInvalidJsonHasTwoAttempts() throws Exception {
        var model=mock(ReviewModelClient.class); var turns=List.of(turn(0,0,0,"问题？","INTERVIEWER"),turn(1,0,1,"回答","CANDIDATE"));
        when(model.importJson(anyString(),anyInt())).thenThrow(new ReviewFailedException("TRUNCATED","length",null))
            .thenReturn(json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[]}]}"))
            .thenReturn(json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[1]}]}"));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1)); var result=ImportAnalysis.request(model,json,block,turns,1,System.nanoTime()+10_000_000_000L,0);
        var all=new ArrayList<ImportedQuestion>(); ImportAnalysis.merge(all,ImportAnalysis.parse(result,block,new ArrayList<>(turns)),turns);
        assertEquals(1,all.size()); assertEquals("回答",all.getFirst().answer()); reset(model);
        when(model.importJson(anyString(),anyInt())).thenThrow(new ReviewFailedException("INVALID_JSON","json",null));
        assertThrows(ReviewFailedException.class,()->ImportAnalysis.request(model,json,block,turns,1,System.nanoTime()+10_000_000_000L,0)); verify(model,times(2)).importJson(anyString(),anyInt());
    }
}

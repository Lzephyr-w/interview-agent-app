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
        assertThrows(IllegalArgumentException.class,()->ImportAnalysis.parse(json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[3],\"answerTurnIds\":[4]}]}"),new ImportAnalysis.Block(List.of(),List.of(3,4)),turns));
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
    @Test void rejectsAnswerJumpingToNextQuestionAndHonorsCorrectedRole() throws Exception {
        var turns=new ArrayList<>(List.of(turn(0,0,0,"第一题？","INTERVIEWER"),turn(1,0,0,"第二题？","INTERVIEWER"),turn(2,0,1,"第二题回答","CANDIDATE")));
        var block=new ImportAnalysis.Block(List.of(),List.of(0,1,2));
        assertThrows(IllegalArgumentException.class,()->ImportAnalysis.parse(json.readTree("{\"roles\":[],\"questions\":[{\"questionTurnIds\":[0],\"answerTurnIds\":[2]},{\"questionTurnIds\":[1],\"answerTurnIds\":[2]}]}"),block,turns));
        var t=turns.get(2); turns.set(2,new ImportTurn(2,0,1,2000L,3000L,t.text(),"INTERVIEWER",true));
        assertThrows(IllegalArgumentException.class,()->ImportAnalysis.parse(json.readTree("{\"roles\":[{\"turnId\":2,\"role\":\"CANDIDATE\"}],\"questions\":[{\"questionTurnIds\":[1],\"answerTurnIds\":[2]}]}"),block,turns));
        assertEquals("INTERVIEWER",turns.get(2).role());
    }
    @Test void transientRetriesAreBoundedAndAuthNeverRetries() throws Exception {
        var model=mock(ReviewModelClient.class); var block=new ImportAnalysis.Block(List.of(),List.of(0)); var turns=List.of(turn(0,0,0,"问题？","UNKNOWN"));
        when(model.importJson(anyString(),anyInt())).thenThrow(new ReviewFailedException("HTTP_429","busy",null));
        assertThrows(ReviewFailedException.class,()->ImportAnalysis.request(model,json,block,turns,1,System.nanoTime()+10_000_000_000L,0));
        verify(model,times(3)).importJson(anyString(),anyInt()); reset(model);
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

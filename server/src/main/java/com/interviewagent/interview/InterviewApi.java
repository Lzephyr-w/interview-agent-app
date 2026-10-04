package com.interviewagent.interview;

import java.time.OffsetDateTime;
import java.util.List;

public final class InterviewApi {
    private InterviewApi() {}

    public record InterviewRequest(String company, String role, String interviewRound, OffsetDateTime interviewTime, String interviewPackageId, String status, String result, String notes) {}
    public record QuestionRequest(String questionText, String answerText, String selfAssessment) {}
    public record TranscriptRequest(String transcript) {}
    public record ImportWarning(String code, String message, List<Integer> turnIds) {}
    public record ImportEdit(int turnId,String original,String replacement,String evidenceSource,String evidenceId,String evidence,String reason,boolean uncertain) {}
    public record ImportedQuestion(String question, String answer, int orderIndex, String speakerEvidence, List<Integer> questionTurnIds, List<Integer> answerTurnIds, List<ImportWarning> warnings, boolean reviewConfirmed, String sourceId,String kind,List<String> notes,List<ImportEdit> edits) {
        public ImportedQuestion { questionTurnIds=questionTurnIds==null?List.of():List.copyOf(questionTurnIds); answerTurnIds=answerTurnIds==null?List.of():List.copyOf(answerTurnIds); warnings=warnings==null?List.of():List.copyOf(warnings); sourceId=sourceId==null?questionTurnIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")):sourceId; kind=kind==null?"QA":kind; notes=notes==null?List.of():List.copyOf(notes); edits=edits==null?List.of():List.copyOf(edits); }
        public ImportedQuestion(String question,String answer,int orderIndex,String evidence,List<Integer> q,List<Integer> a,List<ImportWarning> warnings,boolean confirmed,String sourceId) { this(question,answer,orderIndex,evidence,q,a,warnings,confirmed,sourceId,"QA",List.of(),List.of()); }
        public ImportedQuestion(String question,String answer,int orderIndex,String evidence,List<Integer> q,List<Integer> a,List<ImportWarning> warnings,boolean confirmed) { this(question,answer,orderIndex,evidence,q,a,warnings,confirmed,null); }
        public ImportedQuestion(String question, String answer, int orderIndex, String evidence, List<Integer> q, List<Integer> a) { this(question,answer,orderIndex,evidence,q,a,List.of(),false); }
        public ImportedQuestion(String question, String answer, int orderIndex, String evidence) { this(question, answer, orderIndex, evidence, List.of(), List.of()); }
    }
    public record ImportCorrection(String id, int turnId, int start, int end, String original, String replacement, String evidenceSource, Integer evidenceTurnId, int evidenceStart, int evidenceEnd, String evidence, String reason, String error, boolean accepted) {}
    public record ImportResume(String filename, String status, boolean truncated) {}
    public record ImportTextRequest(String interviewId, String transcript) {}
    public record ImportDraftRequest(List<ImportedQuestion> questions, List<String> acceptedCorrectionIds, List<String> excludedQuestionIds) {}
    public record ImportTurn(int id, int segmentIndex, Integer speakerId, Long startMs, Long endMs, String text, String role, boolean roleCorrected) {}
    public record RoleCorrection(int turnId, String role) {}
    public record ImportRolesRequest(List<RoleCorrection> roles) {}
    public record ImportConfirmRequest(InterviewRequest interview, List<ImportedQuestion> questions, List<String> acceptedCorrectionIds, List<String> excludedQuestionIds) {
        public ImportConfirmRequest(InterviewRequest interview, List<ImportedQuestion> questions) { this(interview,questions,null,null); }
    }
    public record InterviewImport(String id, String status, String originalFilename, long sizeBytes, String transcript, String error, List<ImportedQuestion> questions, String finalInterviewId, List<ImportTurn> turns, List<ImportCorrection> corrections, ImportResume resume, String source, List<String> excludedQuestionIds,String organization,List<String> evidenceCards) {}
    public record InterviewSummary(String id, String company, String role, String interviewRound, OffsetDateTime interviewTime, String status, String result, String interviewPackageId, String interviewType, String simulationType) {}
    public record InterviewQuestion(String id, String questionText, String answerText, String selfAssessment, int sortOrder, String aiFeedback) {}
    public record QuestionReview(String questionId, String evaluation, String answerEvidence, String missingEvidence, String improvementAction, String recommendedAnswerStructure, List<String> possibleFollowups) {}
    public record ReviewReport(String id, String readiness, String summary, List<String> weaknessTags, OffsetDateTime createdAt, List<QuestionReview> questionReviews) {}
    public record InterviewDetail(InterviewSummary interview, String notes, String transcript, List<InterviewQuestion> questions, List<ReviewReport> reviews) {}
}

package com.interviewagent.interview;

import static com.interviewagent.interview.InterviewApi.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import com.interviewagent.ai.storage.AudioTranscriptionService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

@Service
class InterviewImportService {
    private static final Logger log = LoggerFactory.getLogger(InterviewImportService.class);
    static final long MAX_AUDIO_BYTES = 800_000_000L;
    private static final long MAX_DIRECT_AUDIO_BYTES = 5_000_000L;

    private static final int MAX_TRANSCRIPT_CHARS = 72_000;
    @Value("${app.interview-import.request-timeout-seconds:180}") private int requestTimeout = 180;
    @Value("${app.interview-import.analysis-budget-seconds:900}") private int analysisBudget = 900;
    private final JdbcClient jdbc;
    private final AudioTranscriptionService transcription;
    private final String ffmpegPath;
    private final ReviewModelClient model;
    private final InterviewService interviews;
    private final ObjectMapper json;

    InterviewImportService(JdbcClient jdbc, AudioTranscriptionService transcription, ReviewModelClient model, InterviewService interviews, ObjectMapper json, @Value("${app.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.jdbc = jdbc; this.transcription = transcription; this.model = model; this.interviews = interviews; this.json = json; this.ffmpegPath = ffmpegPath;
    }

    InterviewImport upload(String userId, String interviewId, MultipartFile file) {
        if (interviewId != null && !interviewId.isBlank()) interviews.ensureEditableQuestions(userId, interviewId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("录音为空，请选择一段录音后重试。");
        String type = validateAudio(file);
        boolean needsSegments = file.getSize() > MAX_DIRECT_AUDIO_BYTES || "audio/webm".equals(type);
        String id = UUID.randomUUID().toString();
        Path staged = null;
        Path segmentDirectory = null;
        List<ImportAudioSegments.Segment> segments = List.of();
        byte[] directAudio = null;
        try {
            if (needsSegments) {
                staged = Files.createTempFile("interview-import-", ".audio");
                file.transferTo(staged);
                if (Files.size(staged) != file.getSize()) throw new IllegalArgumentException("录音上传不完整，请重新选择文件。");
            } else {
                try (InputStream input = file.getInputStream()) { directAudio = input.readAllBytes(); }
                if (directAudio.length != file.getSize()) throw new IllegalArgumentException("录音读取不完整，请重新选择文件。");
            }
        } catch (IllegalArgumentException exception) {
            deleteTemp(staged);
            throw exception;
        } catch (Exception exception) {
            deleteTemp(staged);
            throw new IllegalArgumentException("读取录音失败，请重新选择文件。", exception);
        }
        try {
            jdbc.sql("INSERT INTO interview_audio_imports (id,user_id,target_interview_id,original_filename,content_type,size_bytes,object_path,status) VALUES (:id,:user,:target,:name,:type,:size,NULL,'TRANSCRIBING')")
                .param("id", id).param("user", userId).param("target", interviewId).param("name", safeName(file.getOriginalFilename())).param("type", type).param("size", file.getSize()).update();
            try {
                List<ImportTurn> turns = new ArrayList<>();
                if (needsSegments) {
                    segments = segmentAudio(staged);
                    segmentDirectory = segments.getFirst().path().getParent();
                    for (int i = 0; i < segments.size(); i++) {
                        var segment = segments.get(i);
                        byte[] bytes;
                        try { bytes = Files.readAllBytes(segment.path()); }
                        catch (Exception exception) { throw new IllegalStateException("读取音频分段失败。", exception); }
                        if (bytes.length > MAX_DIRECT_AUDIO_BYTES) throw new IllegalStateException("音频分段超过腾讯云 5 MB 限制。");
                        appendTurns(turns, transcription.transcribeImport(id, bytes, i, segment.offsetMs()));
                        saveTranscript(userId, id, turns, "TRANSCRIBING");
                    }
                } else appendTurns(turns, transcription.transcribeImport(id, directAudio, 0, 0));
                saveTranscript(userId, id, turns, "ANALYZING");
            } catch (RuntimeException exception) {
                log.warn("importId={} stage=ASR errorType={}", id, exception.getClass().getSimpleName());
                fail(userId, id, "TRANSCRIPTION_FAILED", "录音转写失败：" + message(exception, "请重新上传。"));
                return get(userId, id);
            }
            deleteTemp(staged); staged = null;
            deleteTempDirectory(segmentDirectory); segmentDirectory = null;
            return analyzeTask(userId, id);
        } finally {
            deleteTemp(staged);
            deleteTempDirectory(segmentDirectory);
        }
    }

    InterviewImport get(String userId, String id) { return api(task(userId, id)); }

    InterviewImport pending(String userId,String interviewId) {
        interviews.ensureEditableQuestions(userId,interviewId);
        // Latest attempt wins, including SAVED: an older abandoned draft must not reappear after confirmation.
        var id=jdbc.sql("SELECT id FROM interview_audio_imports WHERE user_id=:user AND target_interview_id=:target ORDER BY created_at DESC,id DESC LIMIT 1")
            .param("user",userId).param("target",interviewId).query(String.class).optional().orElse(null);
        if(id==null) return null;
        var row=task(userId,id);
        return row.finalInterviewId!=null||row.status.equals("SAVED")?null:api(row);
    }

    InterviewImport importText(String userId,ImportTextRequest request) {
        if(request==null||request.interviewId()==null||request.interviewId().isBlank()) throw new IllegalArgumentException("请选择本场面试。");
        interviews.ensureEditableQuestions(userId,request.interviewId());
        limit(request.transcript(),"转写文本",40_000);
        String raw=request.transcript(),id=UUID.randomUUID().toString();
        if(raw.length()>40_000) throw new IllegalArgumentException("转写文本超过40000字符，请缩短后重试。");
        jdbc.sql("INSERT INTO interview_audio_imports(id,user_id,target_interview_id,original_filename,content_type,size_bytes,status) VALUES(:id,:user,:target,'粘贴转写.txt','text/plain',:size,'ANALYZING')")
            .param("id",id).param("user",userId).param("target",request.interviewId()).param("size",raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).update();
        saveTranscript(userId,id,textTurns(raw),"ANALYZING");
        jdbc.sql("UPDATE interview_audio_imports SET transcript=:raw WHERE id=:id AND user_id=:user").param("raw",raw).param("id",id).param("user",userId).update();
        return analyzeTask(userId,id);
    }

    @Transactional
    InterviewImport saveDraft(String userId,String id,ImportDraftRequest request) {
        ImportRow row=task(userId,id,true);
        updateDraft(userId,row,request,false);
        return get(userId,id);
    }

    InterviewImport analyze(String userId, String id, boolean force) {
        ImportRow row = task(userId, id);
        if(row.status.equals("ANALYZING")||row.status.equals("TRANSCRIBING")) throw new IllegalArgumentException("任务处理中，请稍后重试。");
        if (row.transcript.isBlank()) throw new IllegalArgumentException("尚无可分析的转写文本，请重新上传录音。");
        if (row.finalInterviewId != null) return api(row);
        return analyzeTask(userId, id, force, false);
    }

    @Transactional
    InterviewDetail confirm(String userId, String id, ImportConfirmRequest request) {
        ImportRow row = task(userId, id, true);
        if (row.finalInterviewId != null) return interviews.get(userId, row.finalInterviewId);
        if (row.transcript.isBlank()) throw new IllegalArgumentException("尚无可保存的转写文本。");
        if (request == null) throw new IllegalArgumentException("请至少保留一道问答后再保存。");
        updateDraft(userId,row,new ImportDraftRequest(request.questions(),request.acceptedCorrectionIds(),request.excludedQuestionIds()),true);
        List<QuestionRequest> questions = confirmedQuestions(get(userId,id).questions());
        InterviewDetail detail = row.targetInterviewId == null ? createLegacy(userId, request, questions) : interviews.appendQuestions(userId, row.targetInterviewId, questions);
        jdbc.sql("UPDATE interview_audio_imports SET status='SAVED',final_interview_id=:final,error='',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND final_interview_id IS NULL")
            .param("final", detail.interview().id()).param("id", id).param("user", userId).update();
        return detail;
    }

    private InterviewDetail createLegacy(String userId, ImportConfirmRequest request, List<QuestionRequest> questions) {
        if (request == null || request.interview() == null) throw new IllegalArgumentException("请补全本次面试信息。");
        return interviews.createWithQuestions(userId, request.interview(), questions);
    }

    static void appendTurns(List<ImportTurn> all, AudioTranscriptionService.Transcript transcript) {
        if (transcript == null) throw new IllegalStateException("ASR 未返回转写结果。");
        for (var t : transcript.turns()) all.add(new ImportTurn(all.size(),t.segmentIndex(),t.speakerId(),t.startMs(),t.endMs(),t.text(),"UNKNOWN",false));
        all.sort(java.util.Comparator.comparingLong((ImportTurn t)->t.startMs()==null ? (long)t.segmentIndex()*120000 : t.startMs()).thenComparingInt(ImportTurn::segmentIndex));
        for(int i=0;i<all.size();i++) { var t=all.get(i); all.set(i,new ImportTurn(i,t.segmentIndex(),t.speakerId(),t.startMs(),t.endMs(),t.text(),t.role(),t.roleCorrected())); }
    }
    private void saveTranscript(String user, String id, List<ImportTurn> turns, String status) {
        try {
            String text = limit(turns.stream().map(ImportTurn::text).collect(java.util.stream.Collectors.joining("\n")), "转写文本", MAX_TRANSCRIPT_CHARS);
            jdbc.sql("UPDATE interview_audio_imports SET transcript=:text,transcript_json=:detail,status=:status,error='',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user")
                .param("text", text).param("detail", json.writeValueAsString(turns)).param("status", status).param("id", id).param("user", user).update();
        } catch (java.io.IOException e) { throw new IllegalStateException("保存转写失败。", e); }
    }
    @Transactional
    InterviewImport correctRoles(String user, String id, ImportRolesRequest request) {
        ImportRow row = task(user, id, true);
        if (row.finalInterviewId != null) throw new IllegalArgumentException("已确认的导入不能修改角色。");
        if (row.status.equals("TRANSCRIPTION_FAILED")) throw new IllegalArgumentException("转写尚未完整完成，请重新上传录音。");
        if (row.status.equals("TRANSCRIBING") || row.status.equals("ANALYZING")) throw new IllegalArgumentException("任务处理中，请稍后修改角色。");
        List<ImportTurn> turns = new ArrayList<>(readTurns(row));
        if (request == null || request.roles() == null || request.roles().isEmpty() || turns.isEmpty()) throw new IllegalArgumentException("此任务没有声音分离信息；旧任务需要重新上传原音频。");
        for (RoleCorrection correction : request.roles()) {
            if (correction == null || correction.turnId() < 0 || correction.turnId() >= turns.size() || !ImportAnalysis.validRole(correction.role())) throw new IllegalArgumentException("角色或来源编号无效。");
            ImportTurn old = turns.get(correction.turnId());
            turns.set(old.id(), new ImportTurn(old.id(), old.segmentIndex(), old.speakerId(), old.startMs(), old.endMs(), old.text(), correction.role(), true));
        }
        try {
            jdbc.sql("UPDATE interview_audio_imports SET transcript_json=:detail,status='ANALYSIS_FAILED',analysis_progress_json=NULL,analysis_json=NULL,error='角色已修正，请重新分析问答。',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user")
                .param("detail",json.writeValueAsString(turns)).param("id", id).param("user", user).update();
        } catch(java.io.IOException e) { throw new IllegalStateException("保存角色修正失败。",e); }
        return get(user, id);
    }
    private List<ImportTurn> readTurns(ImportRow row) {
        try { return row.detail == null ? List.of() : json.readValue(row.detail, new com.fasterxml.jackson.core.type.TypeReference<List<ImportTurn>>() {}); }
        catch (Exception e) { throw new IllegalStateException("结构化转写格式无效。", e); }
    }
    static List<ImportTurn> textTurns(String transcript) {
        List<ImportTurn> result=new ArrayList<>();
        // Text sources only: no acoustic identity or timestamps are inferred for legacy tasks.
        for(String sentence:transcript.split("(?<=[。？！!?\\n])|(?=面试官：|候选人：|问：|答：)")) {
            for(int offset=0;offset<sentence.length();offset+=1500) {
                String text=sentence.substring(offset,Math.min(offset+1500,sentence.length())).trim();
                if(!text.isBlank()) result.add(new ImportTurn(result.size(),-1,null,null,null,text,"UNKNOWN",false));
            }
        }
        return result;
    }
    private InterviewImport analyzeTask(String userId, String id) {
        return analyzeTask(userId, id, false, true);
    }
    private InterviewImport analyzeTask(String userId, String id, boolean force, boolean initial) {
        ImportRow row = task(userId, id);
        if (row.finalInterviewId != null) return api(row);
        if(!initial&&row.status.equals("ANALYZING")) throw new IllegalArgumentException("任务处理中，请稍后重试。");
        if (row.status.equals("TRANSCRIBING") || row.status.equals("TRANSCRIPTION_FAILED")) throw new IllegalArgumentException("转写尚未完整完成，请重新上传录音。");
        int claimed = jdbc.sql("UPDATE interview_audio_imports SET status='ANALYZING',error='',updated_at=CURRENT_TIMESTAMP" + (force ? ",analysis_progress_json=NULL" : "") + " WHERE id=:id AND user_id=:user AND status=:previous AND final_interview_id IS NULL")
            .param("id", id).param("user", userId).param("previous",row.status).update();
        if (claimed == 0) throw new IllegalArgumentException("任务处理中或已确认，请刷新后重试。");
        long deadline = System.nanoTime() + Math.max(1, analysisBudget) * 1_000_000_000L;
        List<ImportTurn> turns = new ArrayList<>(readTurns(row));
        boolean legacy=turns.isEmpty();
        if(legacy) turns.addAll(textTurns(row.transcript));
        java.util.Map<String, JsonNode> progress = new java.util.LinkedHashMap<>();
        try {
            ResumeSnapshot resume=resume(userId,row.targetInterviewId);
            String name=ImportOrganization.resumeName(resume.text(),resume.info().filename());
            String organizationResume=name.isBlank()?resume.text():"本场候选人姓名："+name+"\n"+resume.text();
            String inputHash=inputHash(turns,resume);
            JsonNode previous=row.progress==null?json.createObjectNode():json.readTree(row.progress);
            boolean compatible=!force&&previous.path("blocks").isObject()&&previous.path("version").asText().equals(ImportOrganization.VERSION)&&previous.path("inputHash").asText().equals(inputHash);
            if(compatible) progress.putAll(json.convertValue(previous.path("blocks"),new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,JsonNode>>(){}));
            else for(int i=0;i<turns.size();i++) { var t=turns.get(i); if(!t.roleCorrected()) turns.set(i,new ImportTurn(t.id(),t.segmentIndex(),t.speakerId(),t.startMs(),t.endMs(),t.text(),"UNKNOWN",false)); }
            List<ImportedQuestion> organized=new ArrayList<>(); List<Integer> editContext=List.of();
            var editBlocks=new ArrayList<>(ImportOrganization.blocks(turns));
            for(int i=0;i<editBlocks.size();i++) {
                var block=new ImportAnalysis.Block(editContext,editBlocks.get(i).added()); String key="organized-"+i;
                JsonNode cached=progress.get(key);
                // Reconstruct a previous truncation split before looking up its checkpoint.
                if(cached!=null&&cached.path("added").isArray()&&cached.path("context").equals(json.valueToTree(block.context()))) {
                    List<Integer> saved=json.convertValue(cached.path("added"),new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>(){});
                    if(!saved.isEmpty()&&saved.size()<block.added().size()&&saved.equals(block.added().subList(0,saved.size()))) {
                        editBlocks.set(i,new ImportAnalysis.Block(List.of(),saved));
                        editBlocks.add(i+1,new ImportAnalysis.Block(List.of(),List.copyOf(block.added().subList(saved.size(),block.added().size()))));
                        block=new ImportAnalysis.Block(editContext,saved);
                    }
                }
                JsonNode result=cached!=null&&cached.path("added").equals(json.valueToTree(block.added()))&&cached.path("context").equals(json.valueToTree(block.context()))?cached.get("result"):null;
                long started=System.nanoTime();
                if(result==null) {
                    try(var task=org.slf4j.MDC.putCloseable("importId",id); var part=org.slf4j.MDC.putCloseable("importBlock",key)) {
                        try { result=ImportOrganization.request(model,json,block,turns,organizationResume,resume.cards(),organized,requestTimeout,deadline); }
                        catch(ReviewFailedException e) {
                            if(!e.code().equals("TRUNCATED")||block.added().size()<2) throw e;
                            int mid=block.added().size()/2;
                            editBlocks.set(i,new ImportAnalysis.Block(List.of(),block.added().subList(0,mid)));
                            editBlocks.add(i+1,new ImportAnalysis.Block(List.of(),block.added().subList(mid,block.added().size())));
                            i--; continue;
                        }
                    }
                    // Cache only after cross-block continuity validation, so bad continuations can be retried.
                }
                var next=ImportOrganization.parse(result,block,turns,organizationResume,resume.cards()); ImportOrganization.merge(organized,next);
                if(organized.size()>80) throw new IllegalArgumentException("整理话题超过80条，请手工整理。");
                progress.put(key,json.valueToTree(java.util.Map.of("added",block.added(),"context",block.context(),"result",result)));
                jdbc.sql("UPDATE interview_audio_imports SET analysis_progress_json=:progress,analysis_json=:analysis,transcript_json=:detail WHERE id=:id AND user_id=:user")
                    .param("progress",json.writeValueAsString(java.util.Map.of("version",ImportOrganization.VERSION,"inputHash",inputHash,"blocks",progress)))
                    .param("analysis",json.writeValueAsString(java.util.Map.of("questions",ImportOrganization.boundaries(organized,turns),"corrections",List.of(),"resume",resume,"excludedQuestionIds",List.of(),"organization",ImportOrganization.VERSION)))
                    .param("detail",legacy?null:json.writeValueAsString(turns)).param("id",id).param("user",userId).update();
                editContext=ImportOrganization.context(organized,block,turns);
                log.info("importId={} stage=ORGANIZATION block={} topics={} outputChars={} elapsedMs={}",id,i,organized.size(),result.toString().length(),(System.nanoTime()-started)/1_000_000);
            }
            jdbc.sql("UPDATE interview_audio_imports SET status='READY',error='',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user").param("id",id).param("user",userId).update();
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            String code=e instanceof ReviewFailedException failure?failure.code():e instanceof InterruptedException?"INTERRUPTED":"BUSINESS_FIELDS";
            log.warn("importId={} stage=ANALYSIS errorType={} causeType={}",id,code,e.getCause()==null?e.getClass().getSimpleName():e.getCause().getClass().getSimpleName());
            fail(userId,id,"ANALYSIS_FAILED","问答分析失败 ["+code+"]："+message(e,"请重试或手工整理。"));
        }
        return get(userId,id);
    }
    private List<QuestionRequest> confirmedQuestions(List<ImportedQuestion> items) {
        if (items == null || items.isEmpty() || items.size() > 80) throw new IllegalArgumentException("请保留 1 到 80 条有效问答后再保存。");
        List<QuestionRequest> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ImportedQuestion item = items.get(i);
            if (item == null || item.orderIndex() != i + 1 || text(item.question(), 4_000).isBlank()) throw new IllegalArgumentException("问题不能为空且顺序必须连续，请检查后重试。");
            String answer = text(item.answer(), 20_000);
            String question=text(item.question(),4000);
            if(item.kind().equals("CANDIDATE_QUESTION")) { question="【候选人反问】"+question; if(!answer.isBlank()) answer="【面试官回答】"+answer; }
            if(item.kind().equals("INTERVIEWER_NOTE")) { question="【面试官说明】"+question; if(!answer.isBlank()) answer="【面试官发言】"+answer; }
            result.add(new QuestionRequest(text(question,4000),text(answer,20000),answer.isBlank()?"UNANSWERED":"UNCERTAIN"));
        }
        return result;
    }

    private record ResumeSnapshot(String fileId,ImportResume info,String text,List<ImportOrganization.Evidence> cards) {}
    private ResumeSnapshot resume(String user,String target) {
        if(target==null) return new ResumeSnapshot("",new ImportResume("","NONE",false),"",List.of());
        var snapshot=jdbc.sql("SELECT rf.id,rf.original_filename,rf.parsed_status,rf.parsed_text,rf.parsed_truncated FROM interviews i JOIN interview_packages p ON p.id=i.interview_package_id AND p.user_id=:user LEFT JOIN resume_files rf ON rf.id=p.resume_file_id AND rf.user_id=:user WHERE i.id=:target AND i.user_id=:user")
            .param("user",user).param("target",target).query((rs,n)-> {
                String id=rs.getString(1),name=rs.getString(2),status=rs.getString(3),raw=rs.getString(4);
                String value="READY".equals(status)&&raw!=null?raw:"";
                return new ResumeSnapshot(id==null?"":id,new ImportResume(name==null?"":name,status==null?"NONE":status,rs.getBoolean(5)||value.length()>12000),value.substring(0,Math.min(value.length(),12000)),List.of());
            }).optional().orElse(new ResumeSnapshot("",new ImportResume("","NONE",false),"",List.of()));
        var linked=jdbc.sql("SELECT c.id,c.project_name,c.technology_stack,c.project_description_and_responsibilities,c.project_highlights FROM interviews i JOIN interview_packages p ON p.id=i.interview_package_id AND p.user_id=:user JOIN interview_package_evidence_cards link ON link.interview_package_id=p.id JOIN project_evidence_cards c ON c.id=link.evidence_card_id AND c.user_id=:user WHERE i.id=:target AND i.user_id=:user ORDER BY c.id")
            .param("user",user).param("target",target).query((rs,n)->new ImportOrganization.Evidence(rs.getString(1),rs.getString(2),"项目："+rs.getString(2)+"\n技术栈："+rs.getString(3)+"\n职责："+rs.getString(4)+"\n亮点："+rs.getString(5))).list();
        List<ImportOrganization.Evidence> cards=new ArrayList<>(); int remaining=12000;
        for(var c:linked) { if(remaining<=0) break; String value=c.text().substring(0,Math.min(remaining,c.text().length())); cards.add(new ImportOrganization.Evidence(c.id(),c.name(),value)); remaining-=value.length(); }
        return new ResumeSnapshot(snapshot.fileId(),snapshot.info(),snapshot.text(),List.copyOf(cards));
    }
    private String inputHash(List<ImportTurn> turns,ResumeSnapshot resume) throws Exception {
        var original=turns.stream().map(t->new ImportTurn(t.id(),t.segmentIndex(),t.speakerId(),t.startMs(),t.endMs(),t.text(),t.roleCorrected()?t.role():"UNKNOWN",t.roleCorrected())).toList();
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(java.util.List.of(original,resume))));
    }
    private List<ImportCorrection> corrections(ImportRow row) {
        try { JsonNode root=row.analysis==null?json.createObjectNode():json.readTree(row.analysis); return root.has("corrections")?json.convertValue(root.path("corrections"),new com.fasterxml.jackson.core.type.TypeReference<List<ImportCorrection>>(){}):List.of(); }
        catch(Exception e) { throw new IllegalStateException("纠错记录格式无效，请重新识别。",e); }
    }
    private List<Integer> checkedIds(List<Integer> ids,List<ImportTurn> turns) {
        java.util.Set<Integer> result=new java.util.TreeSet<>();
        for(Integer id:ids) { if(id==null||id<0||id>=turns.size()) throw new IllegalArgumentException("问答来源编号无效。"); result.add(id); }
        return List.copyOf(result);
    }
    private void updateDraft(String user,ImportRow row,ImportDraftRequest request,boolean confirming) {
        if(row.finalInterviewId!=null||row.status.equals("ANALYZING")||row.status.equals("TRANSCRIBING")) throw new IllegalArgumentException("任务处理中或已保存，不能修改草稿。");
        if(request==null||request.questions()==null||request.questions().size()>80) throw new IllegalArgumentException("问答草稿无效。");
        try {
            var current=api(row);
            boolean organized=ImportOrganization.isOrganized(current.organization());
            var turns=readTurns(row); if(turns.isEmpty()) turns=textTurns(row.transcript);
            var oldCorrections=corrections(row); var selected=ImportCorrections.select(oldCorrections,request.acceptedCorrectionIds());
            var oldDisplay=ImportCorrections.display(turns,oldCorrections); var display=ImportCorrections.display(turns,selected);
            List<String> excluded=request.excludedQuestionIds()==null?current.excludedQuestionIds():List.copyOf(request.excludedQuestionIds());
            java.util.Set<String> known=new java.util.HashSet<>(current.excludedQuestionIds()); for(var q:current.questions()) known.add(q.sourceId());
            if(!known.containsAll(excluded)) throw new IllegalArgumentException("排除的问答来源编号无效。");
            List<ImportedQuestion> updated=new ArrayList<>(); java.util.Set<String> sources=new java.util.HashSet<>();
            for(var item:request.questions()) {
                if(item==null||item.orderIndex()!=updated.size()+1) throw new IllegalArgumentException("问答草稿不能为空且顺序必须连续。");
                var q=checkedIds(item.questionTurnIds(),turns); var a=checkedIds(item.answerTurnIds(),turns);
                if(!known.contains(item.sourceId())&&!item.sourceId().equals(q.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))) throw new IllegalArgumentException("问答来源标识无效。");
                if(!item.sourceId().isBlank()&&!sources.add(item.sourceId())) throw new IllegalArgumentException("问答来源重复。");
                var old=current.questions().stream().filter(value->value.sourceId().equals(item.sourceId())).findFirst()
                    .orElse(new ImportedQuestion(item.question(),item.answer(),item.orderIndex(),item.speakerEvidence(),q,a));
                if(old.sourceSpan()!=null) {
                    if(!old.sourceSpan().equals(item.sourceSpan())||!q.isEmpty()||!a.isEmpty()) throw new IllegalArgumentException("请保留原文对照范围，不能删除或替换来源绕过核对。");
                } else {
                    if(item.sourceSpan()!=null) throw new IllegalArgumentException("不能为手工问答伪造原文范围。");
                    if(known.contains(item.sourceId())&&!item.sourceId().isBlank()&&(old.questionTurnIds().isEmpty()?a.isEmpty():q.isEmpty())) throw new IllegalArgumentException("请保留或修正来源，不能删除来源绕过待确认。");
                }
                if(excluded.contains(item.sourceId())) throw new IllegalArgumentException("已排除的问答不能同时加入。");
                String question=text(item.question(),4000),answer=text(item.answer(),20000);
                if(question.isBlank()) throw new IllegalArgumentException("请先填写问题，再保存预览修改。");
                if(!organized) {
                    if(!q.isEmpty()&&question.equals(ImportAnalysis.text(old.questionTurnIds(),oldDisplay))) question=ImportAnalysis.text(q,display);
                    if(answer.equals(ImportAnalysis.text(old.answerTurnIds(),oldDisplay))) answer=ImportAnalysis.text(a,display);
                } else if(!q.equals(old.questionTurnIds())||!a.equals(old.answerTurnIds())) {
                    // Changing citations invalidates generated prose; preserve only explicitly edited text.
                    if(question.equals(old.question())) question=q.isEmpty()?old.question():ImportAnalysis.text(q,turns);
                    if(answer.equals(old.answer())) answer=ImportAnalysis.text(a,turns);
                }
                var warnings=old.sourceSpan()!=null?new ArrayList<ImportWarning>():organized?ImportOrganization.warnings(old.kind(),q,a,turns):new ArrayList<>(q.isEmpty()?List.<ImportWarning>of():ImportAnalysis.warnings(q,a,turns));
                if(organized) for(var warning:old.warnings()) if(ImportOrganization.REVIEW_WARNINGS.contains(warning.code())) warnings.add(warning);
                updated.add(new ImportedQuestion(question,answer,updated.size()+1,item.speakerEvidence()==null?"":text(item.speakerEvidence(),2000),q,a,warnings,item.reviewConfirmed(),item.sourceId(),old.kind(),old.notes(),old.edits(),old.sourceSpan()));
            }
            updated=new ArrayList<>(organized?ImportOrganization.boundaries(updated,turns):ImportAnalysis.boundaries(updated));
            for(var old:current.questions()) if((old.sourceSpan()!=null||!old.warnings().isEmpty())&&!sources.contains(old.sourceId())&&!excluded.contains(old.sourceId())) throw new IllegalArgumentException("请明确核对或排除待确认问答，不能隐去其来源。");
            if(confirming) for(var q:updated) {
                if(q.sourceSpan()!=null&&q.kind().equals("UNASSIGNED")&&q.question().equals("未整理的原文")) throw new IllegalArgumentException("请补充未整理的内容或明确排除，不能将原文提示作为问答加入。");
                if((q.sourceSpan()!=null||!q.warnings().isEmpty())&&!q.reviewConfirmed()) throw new IllegalArgumentException("请先核对整理稿及不确定内容，或明确排除。");
            }
            var root=row.analysis==null?json.createObjectNode():(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(row.analysis);
            root.set("questions",json.valueToTree(updated)); root.set("corrections",json.valueToTree(selected)); root.set("excludedQuestionIds",json.valueToTree(excluded));
            jdbc.sql("UPDATE interview_audio_imports SET analysis_json=:analysis,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user")
                .param("analysis",json.writeValueAsString(root)).param("id",row.id).param("user",user).update();
        } catch(java.io.IOException e) { throw new IllegalStateException("保存问答草稿失败。",e); }
    }

    private List<ImportedQuestion> parse(JsonNode root,List<ImportTurn> turns) {
        JsonNode items = root.path("questions");
        if (!items.isArray() || items.size() > 80) throw new IllegalArgumentException("模型 JSON 未返回有效的 questions 数组。");
        List<ImportedQuestion> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.path("orderIndex").isIntegralNumber() || !item.path("orderIndex").canConvertToInt() || item.path("orderIndex").asInt() != i + 1) throw new IllegalArgumentException("模型 JSON 的问答顺序无效。");
            String question = text(item.path("question").isTextual() ? item.path("question").asText() : null, 4_000);
            String answer = text(item.path("answer").isTextual() ? item.path("answer").asText() : null, 20_000);
            String evidence = text(item.path("speakerEvidence").isTextual() ? item.path("speakerEvidence").asText() : null, 2_000);
            if (question.isBlank()) throw new IllegalArgumentException("模型 JSON 存在空问题。");
            List<Integer> questionIds=item.has("questionTurnIds") ? json.convertValue(item.path("questionTurnIds"), new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {}) : List.of();
            List<Integer> answerIds=item.has("answerTurnIds") ? json.convertValue(item.path("answerTurnIds"), new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {}) : List.of();
            List<ImportWarning> warnings=item.has("warnings")?json.convertValue(item.path("warnings"),new com.fasterxml.jackson.core.type.TypeReference<List<ImportWarning>>(){}):List.of();
            List<String> notes=item.has("notes")?json.convertValue(item.path("notes"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}):List.of();
            List<ImportEdit> edits=item.has("edits")?json.convertValue(item.path("edits"),new com.fasterxml.jackson.core.type.TypeReference<List<ImportEdit>>(){}):List.of();
            ImportSourceSpan source=item.hasNonNull("sourceSpan")?ImportOrganization.span(item.path("sourceSpan"),java.util.stream.IntStream.range(0,turns.size()).boxed().collect(java.util.stream.Collectors.toSet())):null;
            if(!ImportOrganization.isOrganized(root.path("organization").asText())) { question=compactGeneratedText(question,questionIds,turns); answer=compactGeneratedText(answer,answerIds,turns); }
            result.add(new ImportedQuestion(question,answer,i+1,evidence,questionIds,answerIds,warnings,item.path("reviewConfirmed").asBoolean(false),item.has("sourceId")?item.path("sourceId").asText():null,item.path("kind").asText("QA"),notes,edits,source));
        }
        return result;
    }
    static String compactGeneratedText(String stored,List<Integer> ids,List<ImportTurn> turns) {
        if(ids==null || ids.isEmpty() || ids.stream().anyMatch(i->i==null || i<0 || i>=turns.size())) return stored;
        // Only replace the old generated separator; preserve user edits and paragraphs inside original turns.
        return stored.equals(ImportAnalysis.text(ids,turns,"\n")) ? ImportAnalysis.text(ids,turns) : stored;
    }

    private void fail(String userId, String id, String status, String error) { jdbc.sql("UPDATE interview_audio_imports SET status=:status,error=:error,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user").param("status", status).param("error", error).param("id", id).param("user", userId).update(); }
    private ImportRow task(String userId,String id) { return task(userId,id,false); }
    private ImportRow task(String userId, String id, boolean lock) { return jdbc.sql("SELECT id,status,original_filename,size_bytes,transcript,error,analysis_json,final_interview_id,target_interview_id,transcript_json,analysis_progress_json,content_type FROM interview_audio_imports WHERE id=:id AND user_id=:user"+(lock?" FOR UPDATE":"")).param("id", id).param("user", userId).query((rs, row) -> new ImportRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),rs.getString(12))).optional().orElseThrow(() -> new NoSuchElementException("导入任务不存在或无权访问。")); }
    private InterviewImport api(ImportRow row) {
        try {
            List<ImportTurn> turns=readTurns(row);
            List<ImportedQuestion> questions=row.analysis==null ? List.of() : parse(json.readTree(row.analysis),turns.isEmpty()?textTurns(row.transcript):turns);
            JsonNode analysis=row.analysis==null?json.createObjectNode():json.readTree(row.analysis);
            ImportResume reference=analysis.has("resume")?json.treeToValue(analysis.path("resume").path("info"),ImportResume.class):new ImportResume("","NONE",false);
            List<String> excluded=analysis.has("excludedQuestionIds")?json.convertValue(analysis.path("excludedQuestionIds"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}):List.of();
            List<String> cards=new ArrayList<>(); for(var c:analysis.path("resume").path("cards")) cards.add(c.path("name").asText());
            return new InterviewImport(row.id,row.status,row.filename,row.size,row.transcript,row.error,questions,row.finalInterviewId,turns,corrections(row),reference,row.contentType.equals("text/plain")?"TEXT":"AUDIO",excluded,analysis.path("organization").asText(""),List.copyOf(cards));
        } catch(Exception e) { throw new IllegalStateException("导入结果格式无效，请重新分析。",e); }
    }
    private static String validateAudio(MultipartFile file) {
        validateAudioSize(file.getSize());
        try (InputStream input=file.getInputStream()) { return validateAudioHeader(input.readNBytes(16)); }
        catch (Exception exception) { throw new IllegalArgumentException("读取录音失败，请重新选择文件。", exception); }
    }
    static void validateAudioSize(long size) { if (size <= 0) throw new IllegalArgumentException("录音为空，请选择一段录音后重试。"); if (size > MAX_AUDIO_BYTES) throw new IllegalArgumentException("录音超过 800 MB，请缩短或压缩后重试。"); }
    List<ImportAudioSegments.Segment> segmentAudio(Path input) {
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("interview-audio-parts-");
            Path pattern = directory.resolve("audio.pcm");
            process = new ProcessBuilder(ffmpegPath, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", input.toString(), "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "s16le", pattern.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.HOURS)) {
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                throw new IllegalStateException("音频分段处理超时，请稍后重试。");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("音频分段失败，请确认服务器已安装 FFmpeg，且录音文件未损坏。");
            List<ImportAudioSegments.Segment> result = ImportAudioSegments.split(pattern, directory);
            Files.delete(pattern);
            if (result.isEmpty()) throw new IllegalStateException("没有生成可识别的音频片段。");
            return result;
        } catch (IllegalStateException exception) { deleteTempDirectory(directory); throw exception; }
        catch (InterruptedException exception) { if (process != null) process.destroyForcibly(); Thread.currentThread().interrupt(); deleteTempDirectory(directory); throw new IllegalStateException("音频分段处理已中断。", exception); }
        catch (Exception exception) { if (process != null && process.isAlive()) process.destroyForcibly(); deleteTempDirectory(directory); throw new IllegalStateException("音频分段失败，请确认服务器已安装 FFmpeg。", exception); }
    }
    private static void deleteTemp(Path file) { if (file != null) try { Files.deleteIfExists(file); } catch (Exception exception) { log.warn("temporary interview audio cleanup failed: {}", exception.getClass().getSimpleName()); } }
    private static void deleteTempDirectory(Path directory) { if (directory != null) try (var files = Files.list(directory)) { for (Path file : files.toList()) deleteTemp(file); Files.deleteIfExists(directory); } catch (Exception exception) { log.warn("temporary interview audio cleanup failed: {}", exception.getClass().getSimpleName()); } }
    private static String validateAudioHeader(byte[] header) { String type = audioType(header); if (type == null) throw new IllegalArgumentException("仅支持 WebM、Ogg、MP3、MP4/M4A 或 WAV 音频，文件内容必须与格式一致。"); return type; }
    private static String audioType(byte[] b) { if (b.length > 12 && b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='A'&&b[10]=='V'&&b[11]=='E') return "audio/wav"; if (b.length > 4 && b[0]=='O'&&b[1]=='g'&&b[2]=='g'&&b[3]=='S') return "audio/ogg"; if (b.length > 4 && b[0]==0x1a&&b[1]==0x45&&b[2]==(byte)0xdf&&b[3]==(byte)0xa3) return "audio/webm"; if (b.length > 12 && b[4]=='f'&&b[5]=='t'&&b[6]=='y'&&b[7]=='p') return "audio/mp4"; if (b.length > 3 && b[0]=='I'&&b[1]=='D'&&b[2]=='3' || b.length > 2 && (b[0]&0xff)==0xff && (b[1]&0xe0)==0xe0) return "audio/mpeg"; return null; }
    private static String safeName(String value) { return value == null || value.isBlank() ? "recording" : value.replaceAll("[^\\p{L}\\p{N}._-]", "_"); }
    private static String text(String value, int maximum) { String result = value == null ? "" : value.trim(); if (result.length() > maximum) throw new IllegalArgumentException("模型 JSON 字段过长。"); return result; }
    private static String limit(String value, String label, int maximum) { String result = value == null ? "" : value.trim(); if (result.isBlank()) throw new IllegalArgumentException(label + "为空，请重新上传。"); if (result.length() > maximum) throw new IllegalArgumentException(label + "超过 " + maximum + " 个字符，请缩短录音后重试。"); return result; }
    private static String message(Exception exception, String fallback) { String value = exception.getMessage(); return value == null || value.isBlank() ? fallback : value; }
    private record ImportRow(String id, String status, String filename, long size, String transcript, String error, String analysis, String finalInterviewId, String targetInterviewId, String detail, String progress,String contentType) {}
}

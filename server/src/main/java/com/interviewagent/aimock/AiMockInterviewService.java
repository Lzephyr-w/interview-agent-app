package com.interviewagent.aimock;

import static com.interviewagent.aimock.AiMockInterviewApi.*;
import static com.interviewagent.interview.InterviewApi.*;
import com.interviewagent.interview.InterviewService;
import com.interviewagent.ai.storage.AiAudioStorage;
import com.interviewagent.ai.storage.AudioTranscriptionService;
import com.interviewagent.ai.AiMockTaskService;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.ai.SimulationMaterials;
import com.interviewagent.ai.SimulationContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.knowledge.KnowledgeService;
import com.interviewagent.ai.AiMockTaskService.ClaimedTask;
import static com.interviewagent.aimock.AiMockQuestionAgent.*;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AiMockInterviewService {
    private static final long MAX_AUDIO_BYTES = 10 * 1024 * 1024;
    private static final int AUDIO_CHUNK_BYTES = 1024 * 1024;
    private static final Duration UPLOAD_GRACE = Duration.ofMinutes(15);
    private static final int MAX_TRANSCRIPT_CHARS = 40_000;
    private static final int LEGACY_QUESTION_LIMIT = 3;
    private static final String KNOWLEDGE_VERSION = "KNOWLEDGE_INCREMENTAL_V1";
    private static final Logger log = LoggerFactory.getLogger(AiMockInterviewService.class);
    private final JdbcClient jdbc; private final InterviewService interviews; private final AiMockQuestionAgent questionAgent; private final AiAudioStorage storage;
    private final AudioTranscriptionService transcription;
    private final AiMockTaskService tasks;
    private final SimulationMaterials materials;
    private final AgentPythonClient agent;
    private final KnowledgeService knowledge;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final TransactionTemplate creation;
    private final InterviewPackagePreparationService preparations;
    AiMockInterviewService(JdbcClient jdbc, InterviewService interviews, AiMockQuestionAgent questionAgent, AiAudioStorage storage, AudioTranscriptionService transcription, AiMockTaskService tasks, SimulationMaterials materials, AgentPythonClient agent, KnowledgeService knowledge, ObjectMapper json, PlatformTransactionManager manager, InterviewPackagePreparationService preparations) { this.jdbc = jdbc; this.interviews = interviews; this.questionAgent = questionAgent; this.storage = storage; this.transcription = transcription; this.tasks = tasks; this.materials=materials; this.agent=agent; this.knowledge=knowledge; this.json=json; this.transaction=new TransactionTemplate(manager); this.creation=new TransactionTemplate(manager); this.creation.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ); this.preparations=preparations; }

    Session create(String userId, StartRequest request) {
        return createFrozen(userId,request,false);
    }
    Session prepare(String userId, StartRequest request) {
        return createFrozen(userId,request,true);
    }
    private Session createFrozen(String userId, StartRequest request, boolean prepared) {
        // A concurrent creator may be invisible to this repeatable-read snapshot; retry the whole short transaction.
        for (int attempt=0;;attempt++) {
            try { return creation.execute(status->create(userId,request,prepared)); }
            catch (org.springframework.dao.DuplicateKeyException | org.springframework.dao.CannotSerializeTransactionException error) {
                if (attempt>=2) throw error;
            }
        }
    }
    private Session create(String userId, StartRequest request,boolean prepared) {
        String packageId = required(request.interviewPackageId(), "面试包");
        PackageInfo p = jdbc.sql("SELECT id, company, role, interview_round FROM interview_packages WHERE id=:id AND user_id=:user").param("id", packageId).param("user", userId).query((rs, row) -> new PackageInfo(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4))).optional().orElseThrow(AiMockInterviewService::notFound);
        String sourceMode = request.sourceMode()==null || request.sourceMode().isBlank() ? "STANDARD" : request.sourceMode();
        if (!Set.of("STANDARD","KNOWLEDGE").contains(sourceMode)) throw new IllegalArgumentException("模拟资料模式无效。");
        List<String> documentIds = sourceMode.equals("KNOWLEDGE") ? knowledge.selectedDocuments(userId,request.categoryIds()) : List.of();
        var preparation=sourceMode.equals("STANDARD")?preparations.ensure(userId,packageId):null;
        String id = UUID.randomUUID().toString(); OffsetDateTime expires = OffsetDateTime.now().plusMinutes(prepared?15:50);
        // ponytail: the otherwise unused index marks a prepared session; begin resets its 50-minute timer.
        jdbc.sql("INSERT INTO ai_mock_interviews(id,user_id,interview_package_id,company,role,interview_round,status,expires_at,current_question_index,material_snapshot,generation_version,source_mode,knowledge_document_ids,preparation_id) VALUES(:id,:user,:package,:company,:role,:round,'RUNNING',:expires,:index,:snapshot,:version,:sourceMode,:documents,:preparation)").param("id",id).param("user",userId).param("package",packageId).param("company",p.company).param("role",p.role).param("round",p.round).param("expires",expires).param("index",prepared?-1:0).param("snapshot",preparation==null?materials.capture(userId,packageId).toString():preparation.materialSnapshot()).param("version",sourceMode.equals("KNOWLEDGE")?KNOWLEDGE_VERSION:"SIMULATION_AGENT_V1").param("sourceMode",sourceMode).param("documents",String.join(",",documentIds)).param("preparation",preparation==null?null:preparation.id()).update();
        if (preparation==null) tasks.enqueue(userId, sourceMode.equals("KNOWLEDGE")?"AI_FIRST":"AI_PLAN", id, null);
        return detail(userId,id);
    }
    @Transactional Session begin(String userId,String id) {
        lock(userId,id);
        SessionRow s=session(userId,id);
        if(!"RUNNING".equals(s.status) || !s.expiresAt.isAfter(OffsetDateTime.now())) throw new IllegalStateException("模拟已结束或超时，无法开始。");
        jdbc.sql("UPDATE ai_mock_interviews SET current_question_index=0,started_at=CURRENT_TIMESTAMP,expires_at=CURRENT_TIMESTAMP + INTERVAL '50' MINUTE,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND current_question_index=-1")
            .param("id",id).param("user",userId).update();
        if (s.preparationId!=null && s.plan!=null && !hasQuestion(id,0)) tasks.enqueue(userId,"AI_FIRST",id,null);
        return detail(userId,id);
    }
    Session active(String userId) { return jdbc.sql("SELECT id FROM ai_mock_interviews WHERE user_id=:user AND status IN ('RUNNING','TIME_EXPIRED') AND current_question_index>=0 ORDER BY updated_at DESC LIMIT 1").param("user",userId).query(String.class).optional().map(id -> detail(userId,id)).orElseThrow(AiMockInterviewService::notFound); }
    Session get(String userId, String id) { return detail(userId,id); }

    public void processTask(ClaimedTask task) {
        tasks.execute(task, () -> processTaskBody(task));
    }

    private void processTaskBody(ClaimedTask task) {
        switch (task.taskType()) {
            case "AI_PLAN" -> processPlan(task.userId(), task.resourceId());
            case "AI_FIRST" -> processFirst(task.userId(), task.resourceId());
            case "AI_CREATE" -> processCreate(task.userId(), task.resourceId());
            case "AI_NEXT" -> processNext(task.userId(), task.resourceId(), task.relatedId());
            case "AI_PREPARE_NEXT" -> processPrepareNext(task.userId(), task.resourceId(), task.relatedId());
            case "AI_FINALIZE_AUDIO" -> processAudioUpload(task.userId(), task.resourceId(), task.relatedId());
            case "AI_AUDIO" -> processAudio(task.userId(), task.resourceId(), task.relatedId());
            case "AI_FEEDBACK" -> processFeedback(task.userId(),task.resourceId(),task.relatedId());
            default -> throw new IllegalStateException("后台任务类型无效，请稍后重试。");
        }
    }

    void processCreate(String userId,String id) {
        SessionRow s=session(userId,id);
        if (s.plan==null) {
            processPlan(userId,id);
            return;
        }
        addQuestion(userId,id,0);
    }

    private void processPlan(String userId, String id) {
        SessionRow s=session(userId,id);
        if (incremental(s)) { addQuestion(userId,id,0); return; }
        if (s.preparationId!=null && s.plan==null) { applyPreparation(userId,id,preparations.get(userId,s.preparationId)); return; }
        if (s.plan != null) {
            tasks.write(() -> { if (!hasQuestion(id,0)) tasks.enqueue(userId,"AI_FIRST",id,null); });
            return;
        }
        long preparationStarted=System.nanoTime();
        tasks.check();
        JsonNode materials=snapshot(userId,s);
        KnowledgeService.Source firstSource=knowledgeSource(userId,s,s.role+" "+materials.path("jd").asText());
        String planKnowledge=firstSource==null?null:clip("首题依据：\n"+clip(knowledgeText(firstSource),2500)+"\n其他知识主题：\n"+knowledge.overview(userId,documentIds(s)),6000);
        log.info("ai_mock_timing stage=plan_context taskId={} sessionId={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,(System.nanoTime()-preparationStarted)/1_000_000);
        PlanAndFirst planned=questionAgent.planAndFirst(materials,planKnowledge);
        long commitStarted=System.nanoTime();
        try {
            tasks.write(() -> {
                if (hasQuestion(id,0)) return;
                int updated=jdbc.sql("UPDATE ai_mock_interviews SET question_plan=:plan,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND question_plan IS NULL")
                    .param("plan",questionAgent.serialize(planned.plan())).param("id",id).param("user",userId).update();
                if (updated==1 && insertQuestion(id,0,planned.firstQuestion(),firstSource)) prepareNext(userId,id,0,questionLimit(s));
            });
        } finally { log.info("ai_mock_timing stage=plan_commit taskId={} sessionId={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,(System.nanoTime()-commitStarted)/1_000_000); }
    }

    private void processFirst(String userId, String id) {
        SessionRow s=session(userId,id);
        if (s.plan==null && !incremental(s)) processPlan(userId,id); else addQuestion(userId,id,0);
    }

    void processNext(String userId,String id,String questionId) {
        QuestionRow q=question(userId,id,questionId);
        if (q.state.equals("ANSWERED") || q.state.equals("SKIPPED")) {
            boolean[] released={false};
            tasks.write(() -> released[0]=openPrepared(userId,id,q.sortOrder+1,questionLimit(session(userId,id))));
            if (released[0]) return;
            addQuestion(userId,id,q.sortOrder+1,false);
        }
    }

    void processPrepareNext(String userId,String id,String questionId) {
        QuestionRow q=question(userId,id,questionId);
        if (Set.of("OPEN","TRANSCRIBING","ANSWERED","SKIPPED").contains(q.state))
            addQuestion(userId,id,q.sortOrder+1,true);
    }

    void processFeedback(String userId,String id,String questionId) {
        QuestionRow q=question(userId,id,questionId);
        if (!q.state.equals("ANSWERED")) return;
        // Compatibility for an answer created before NEXT was split from FEEDBACK.
        if (q.sortOrder+1<questionLimit(session(userId,id)) && !hasQuestion(id,q.sortOrder+1))
            tasks.write(() -> { if (!hasQuestion(id,q.sortOrder+1)) tasks.enqueue(userId,"AI_NEXT",id,q.id); });
        if (q.feedback.isBlank()) {
            String feedback=feedback(userId,session(userId,id),q,q.answer);
            tasks.write(() -> {
                int updated=jdbc.sql("UPDATE ai_mock_interview_questions SET ai_feedback=:feedback,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state='ANSWERED' AND ai_feedback=''")
                    .param("id",q.id).param("feedback",feedback).update();
                if (updated>0) jdbc.sql("UPDATE ai_mock_audio_assets SET feedback=:feedback,updated_at=CURRENT_TIMESTAMP WHERE user_id=:user AND question_id=:question AND status='READY'")
                    .param("user",userId).param("question",q.id).param("feedback",feedback).update();
            });
        }
    }

    void processAudio(String userId,String id,String assetId) {
        long preparationStarted=System.nanoTime();
        AudioRow asset=audio(userId,assetId);
        if ("READY".equals(asset.status)) return;
        SessionRow interview=session(userId,id);
        QuestionRow q=question(id,asset.questionId);
        if (!Set.of("OPEN","TRANSCRIBING","ANSWERED").contains(q.state)) return;
        try {
            if(!"TRANSCRIBING".equals(asset.status))
                tasks.write(() -> jdbc.sql("UPDATE ai_mock_audio_assets SET status='TRANSCRIBING',transcript_error='' WHERE id=:id AND status<>'READY'").param("id",assetId).update());
            tasks.check();
            String saved=asset.transcript;
            log.info("ai_mock_timing stage=audio_prepare taskId={} sessionId={} questionId={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,q.id,(System.nanoTime()-preparationStarted)/1_000_000);
            byte[] bytes=null;
            if(saved.isBlank()) {
                long downloadStarted=System.nanoTime();
                bytes=loadAudio(asset.path);
                log.info("ai_mock_timing stage=audio_asset_download taskId={} sessionId={} questionId={} bytes={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,q.id,bytes.length,(System.nanoTime()-downloadStarted)/1_000_000);
            }
            long transcriptionStarted=System.nanoTime();
            String detected;
            try { detected=saved.isBlank()?transcription.transcribe(userId,bytes,asset.type,storage.signedUrl(asset.path)):saved; }
            finally { log.info("ai_mock_timing stage=transcription taskId={} sessionId={} questionId={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,q.id,(System.nanoTime()-transcriptionStarted)/1_000_000); }
            final String transcript=detected.isBlank()?"":limited(detected,"转写文本",MAX_TRANSCRIPT_CHARS);
            long commitStarted=System.nanoTime();
            tasks.write(() -> {
                QuestionRow current=question(id,q.id);
                if (!Set.of("OPEN","TRANSCRIBING","ANSWERED").contains(current.state)) return;
                int assetUpdated=jdbc.sql("UPDATE ai_mock_audio_assets SET status='READY',transcript=:text,transcript_error='',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='TRANSCRIBING'")
                    .param("id",assetId).param("text",transcript).update();
                if (assetUpdated==0) return;
                int answerUpdated=jdbc.sql("UPDATE ai_mock_interview_questions SET confirmed_answer_text=:answer,state='ANSWERED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state IN ('OPEN','TRANSCRIBING','ANSWERED') AND confirmed_answer_text=''")
                    .param("answer",transcript).param("id",q.id).update();
                if (answerUpdated>0) {
                    if(!"ANSWERED".equals(current.state)) advanceNext(userId,id,q,questionLimit(interview));
                    if (!transcript.isBlank()) tasks.enqueue(userId,"AI_FEEDBACK",id,q.id);
                }
            });
            log.info("ai_mock_timing stage=audio_commit taskId={} sessionId={} questionId={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,q.id,(System.nanoTime()-commitStarted)/1_000_000);
        } catch (RuntimeException exception) {
            tasks.write(() -> jdbc.sql("UPDATE ai_mock_audio_assets SET status='FAILED',transcript_error=:error,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='TRANSCRIBING'")
                .param("id",assetId).param("error",AiMockTaskService.stableError(exception)).update());
            throw exception;
        }
    }

    private String feedback(String user,SessionRow s,QuestionRow q,String answer) {
        tasks.check();
        Map<String,Object> input=new HashMap<>(Map.of("materials",snapshot(user,s),"history",history(user,s.id),"questionText",q.text,"answer",answer));
        KnowledgeService.Source source=source(q.knowledgeSource);
        if (s.sourceMode.equals("KNOWLEDGE")) {
            if (source==null) throw new IllegalStateException("当前题目的知识库依据不可用，请重试。");
            input.put("knowledge",knowledgeText(source));
        }
        JsonNode result=agent.simulate("VOICE_FEEDBACK",input);
        SimulationContract.modelResult("VOICE_FEEDBACK",result);
        return result.path("feedback").asText();
    }

    private byte[] loadAudio(String path) { return storage.download(path); }

    @Transactional AudioUpload beginAudioUpload(String userId,String id,String questionId,AudioUploadRequest request) {
        long total=request==null?0:request.totalBytes(); String sha=sha(request==null?null:request.sha256());
        if(total<=0||total>MAX_AUDIO_BYTES) throw new IllegalArgumentException("录音超过 10 MiB，请缩短回答后重新录音。");
        QuestionRow q=lockedQuestion(userId,id,questionId);
        UploadRow old=activeUpload(userId,id,questionId);
        if(old!=null) { if(old.totalBytes!=total||!old.sha256.equals(sha)) throw new IllegalArgumentException("已有未完成录音，请继续上传或放弃后重新录音。"); return apiUpload(old); }
        if(!"OPEN".equals(q.state)) throw new IllegalArgumentException("当前题目不能上传录音。");
        if(q.answerExpiresAt==null||!OffsetDateTime.now().isBefore(q.answerExpiresAt)) throw new IllegalArgumentException("本题回答时间已结束，请进入下一题。");
        int parts=(int)((total+AUDIO_CHUNK_BYTES-1)/AUDIO_CHUNK_BYTES); String upload=UUID.randomUUID().toString(); OffsetDateTime expires=q.answerExpiresAt.plus(UPLOAD_GRACE);
        jdbc.sql("INSERT INTO ai_mock_audio_uploads(id,user_id,ai_mock_interview_id,question_id,content_type,total_bytes,total_parts,sha256,status,expires_at) VALUES(:id,:user,:session,:question,'audio/wav',:bytes,:parts,:sha,'UPLOADING',:expires)")
            .param("id",upload).param("user",userId).param("session",id).param("question",questionId).param("bytes",total).param("parts",parts).param("sha",sha).param("expires",expires).update();
        return new AudioUpload(upload,AUDIO_CHUNK_BYTES,total,parts,List.of(),"UPLOADING",expires);
    }

    AudioUpload audioUpload(String userId,String id,String questionId,String uploadId) { return apiUpload(upload(userId,id,questionId,uploadId)); }

    @Transactional void audioUploadPart(String userId,String id,String questionId,String uploadId,int partNo,String range,String partSha,byte[] bytes) {
        UploadRow upload=upload(userId,id,questionId,uploadId); requireUploading(upload);
        String digest=sha(partSha); Range parsed=range(range); long start=(long)partNo*AUDIO_CHUNK_BYTES, end=Math.min(upload.totalBytes-1,start+AUDIO_CHUNK_BYTES-1);
        if(partNo<0||partNo>=upload.totalParts||parsed.start!=start||parsed.end!=end||parsed.total!=upload.totalBytes||bytes==null||bytes.length!=end-start+1||!digest.equals(sha(bytes))) throw new IllegalArgumentException("录音分片校验失败，请继续上传或重新录音。");
        PartRow existing=part(uploadId,partNo);
        if(existing!=null) { if(existing.sizeBytes==bytes.length&&existing.sha256.equals(digest)) return; throw new IllegalArgumentException("录音分片与已确认内容不一致。"); }
        String path="ai-mock-staging/"+uploadId+"/"+partNo;
        try {
            long storageStarted=System.nanoTime();
            try { storage.upload(path,"application/octet-stream",bytes); }
            finally { log.info("ai_mock_timing stage=audio_part_storage_upload sessionId={} questionId={} partNo={} bytes={} elapsed_ms={}",id,questionId,partNo,bytes.length,(System.nanoTime()-storageStarted)/1_000_000); }
            long metadataStarted=System.nanoTime();
            jdbc.sql("INSERT INTO ai_mock_audio_upload_parts(upload_id,part_no,size_bytes,sha256,object_path) VALUES(:upload,:part,:size,:sha,:path)").param("upload",uploadId).param("part",partNo).param("size",bytes.length).param("sha",digest).param("path",path).update();
            log.info("ai_mock_timing stage=audio_part_metadata sessionId={} questionId={} partNo={} elapsed_ms={}",id,questionId,partNo,(System.nanoTime()-metadataStarted)/1_000_000);
        }
        catch(RuntimeException error) { try { storage.delete(path); } catch(RuntimeException ignored) {} throw error; }
    }

    @Transactional Session completeAudioUpload(String userId,String id,String questionId,String uploadId) {
        UploadRow upload=upload(userId,id,questionId,uploadId);
        if("COMPLETED".equals(upload.status)) return detail(userId,id);
        requireUploading(upload);
        List<PartRow> parts=parts(uploadId);
        if(parts.size()!=upload.totalParts) throw new IllegalArgumentException("仍有录音分片未上传完成。");
        int claimed=jdbc.sql("UPDATE ai_mock_interview_questions SET state='TRANSCRIBING',updated_at=CURRENT_TIMESTAMP WHERE id=:question AND ai_mock_interview_id=:session AND state='OPEN' AND EXISTS (SELECT 1 FROM ai_mock_interviews WHERE id=:session AND user_id=:user AND status='RUNNING' AND expires_at>CURRENT_TIMESTAMP) AND EXISTS (SELECT 1 FROM ai_mock_audio_uploads WHERE id=:upload AND user_id=:user AND ai_mock_interview_id=:session AND question_id=:question AND status='UPLOADING')")
            .param("question",questionId).param("session",id).param("user",userId).param("upload",uploadId).update();
        if(claimed==0) {
            if("COMPLETED".equals(upload(userId,id,questionId,uploadId).status)) return detail(userId,id);
            if(!"RUNNING".equals(session(userId,id).status)) return detail(userId,id);
            if(!"OPEN".equals(question(userId,id,questionId).state)) throw new IllegalArgumentException("当前题目不能完成录音上传。");
            throw new IllegalStateException("录音上传状态已变化，请重试。");
        }
        if(jdbc.sql("UPDATE ai_mock_audio_uploads SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='UPLOADING'").param("id",uploadId).update()!=1) throw new IllegalStateException("录音上传状态已变化，请重试。");
        tasks.enqueue(userId,"AI_FINALIZE_AUDIO",id,uploadId);
        return detail(userId,id);
    }

    void processAudioUpload(String userId,String id,String uploadId) {
        long preparationStarted=System.nanoTime();
        UploadRow upload=uploadById(userId,id,uploadId);
        if(!"COMPLETED".equals(upload.status)) return;
        if(upload.completedAssetId!=null) { clearParts(uploadId); return; }
        QuestionRow q=question(userId,id,upload.questionId);
        if(!"TRANSCRIBING".equals(q.state)) { clearParts(uploadId); return; }
        List<PartRow> parts=parts(uploadId);
        if(parts.size()!=upload.totalParts) throw new IllegalStateException("录音分片不完整，请重试。");
        log.info("ai_mock_timing stage=audio_finalize_prepare taskId={} sessionId={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,(System.nanoTime()-preparationStarted)/1_000_000);
        ByteArrayOutputStream merged=new ByteArrayOutputStream((int)upload.totalBytes);
        for(int index=0;index<parts.size();index++) if(parts.get(index).partNo!=index) throw new IllegalArgumentException("录音分片不连续。");
        String taskId=org.slf4j.MDC.get("taskId");
        long downloadsStarted=System.nanoTime();
        // ponytail: batches cap Storage reads at three; keep part order and existing integrity checks.
        try(ExecutorService downloads=Executors.newFixedThreadPool(Math.min(3,parts.size()))) {
            for(int offset=0;offset<parts.size();offset+=3) {
                List<Future<byte[]>> batch=new ArrayList<>();
                for(int index=offset;index<Math.min(offset+3,parts.size());index++) {
                    PartRow part=parts.get(index);
                    batch.add(downloads.submit(() -> {
                        long started=System.nanoTime();
                        byte[] partBytes=storage.download(part.path);
                        log.info("ai_mock_timing stage=audio_part_download taskId={} sessionId={} partNo={} bytes={} elapsed_ms={}",taskId,id,part.partNo,partBytes.length,(System.nanoTime()-started)/1_000_000);
                        if(partBytes.length!=part.sizeBytes||!part.sha256.equals(sha(partBytes))) throw new IllegalArgumentException("已上传录音分片校验失败。");
                        return partBytes;
                    }));
                }
                for(Future<byte[]> future:batch) {
                    try { merged.writeBytes(future.get()); }
                    catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("录音分片下载已中断。",error); }
                    catch(ExecutionException error) { if(error.getCause() instanceof RuntimeException cause) throw cause; throw new IllegalStateException("录音分片下载失败。",error); }
                }
            }
        }
        log.info("ai_mock_timing stage=audio_parts_download_total taskId={} sessionId={} elapsed_ms={}",taskId,id,(System.nanoTime()-downloadsStarted)/1_000_000);
        byte[] bytes=merged.toByteArray(); if(bytes.length!=upload.totalBytes||!upload.sha256.equals(sha(bytes))) throw new IllegalArgumentException("录音完整性校验失败，请重新录音。");
        String type=validateAudio(bytes);
        String asset=UUID.randomUUID().toString();
        boolean reuseSinglePart=parts.size()==1;
        String path=reuseSinglePart?parts.get(0).path:"ai-mock/"+userId+"/"+asset+extension(type);
        boolean[] assetStored={false};
        try {
            if(reuseSinglePart) log.info("ai_mock_timing stage=audio_asset_reuse taskId={} sessionId={} bytes={}",org.slf4j.MDC.get("taskId"),id,bytes.length);
            else { long uploadStarted=System.nanoTime(); storage.upload(path,type,bytes); log.info("ai_mock_timing stage=audio_asset_upload taskId={} sessionId={} bytes={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,bytes.length,(System.nanoTime()-uploadStarted)/1_000_000); }
            long commitStarted=System.nanoTime();
            tasks.write(() -> {
                if(!"TRANSCRIBING".equals(question(userId,id,q.id).state)) return;
                jdbc.sql("INSERT INTO ai_mock_audio_assets(id,user_id,ai_mock_interview_id,question_id,original_filename,content_type,size_bytes,object_path,status,transcript) VALUES(:id,:user,:session,:question,'answer.wav',:type,:size,:path,'TRANSCRIBING','')").param("id",asset).param("user",userId).param("session",id).param("question",q.id).param("type",type).param("size",bytes.length).param("path",path).update();
                jdbc.sql("UPDATE ai_mock_audio_uploads SET completed_asset_id=:asset,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",uploadId).param("asset",asset).update();
                if(reuseSinglePart) jdbc.sql("DELETE FROM ai_mock_audio_upload_parts WHERE upload_id=:upload").param("upload",uploadId).update();
                tasks.enqueue(userId,"AI_AUDIO",id,asset);
                acceptAudio(userId,id,q,questionLimit(session(userId,id)));
                assetStored[0]=true;
            });
            log.info("ai_mock_timing stage=audio_finalize_commit taskId={} sessionId={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,(System.nanoTime()-commitStarted)/1_000_000);
        } catch(RuntimeException error) { if(!reuseSinglePart) try { storage.delete(path); } catch(RuntimeException ignored) {} throw error; }
        if(!reuseSinglePart||!assetStored[0]) clearParts(uploadId);
    }

    @Transactional Session cancelAudioUpload(String userId,String id,String questionId,String uploadId) {
        UploadRow upload=upload(userId,id,questionId,uploadId); if("COMPLETED".equals(upload.status)) throw new IllegalArgumentException("录音已提交，不能放弃上传。");
        lock(userId,id); clearParts(uploadId);
        jdbc.sql("UPDATE ai_mock_audio_uploads SET status='ABORTED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='UPLOADING'").param("id",uploadId).update();
        return detail(userId,id);
    }

    @Scheduled(fixedDelayString="${app.ai-mock-upload.cleanup-ms:3600000}", initialDelayString="${app.ai-mock-upload.cleanup-ms:3600000}")
    @Transactional public void cleanupExpiredAudioUploads() {
        List<UploadRow> expired=jdbc.sql("SELECT id,user_id,ai_mock_interview_id,question_id,content_type,total_bytes,total_parts,sha256,status,expires_at,completed_asset_id FROM ai_mock_audio_uploads WHERE status='UPLOADING' AND expires_at<=CURRENT_TIMESTAMP").query((rs,row)->uploadRow(rs)).list();
        for(UploadRow upload:expired) expireUpload(upload);
    }

    @Transactional public Session expire(String userId, String id, String questionId) {
        if (!"RUNNING".equals(session(userId,id).status)) return detail(userId,id);
        lockRunning(userId,id); QuestionRow q=question(userId,id,questionId);
        UploadRow upload=activeUpload(userId,id,questionId);
        if(upload!=null && OffsetDateTime.now().isBefore(upload.expiresAt)) return detail(userId,id);
        if(upload!=null) expireUpload(upload);
        if (!Set.of("OPEN","TRANSCRIBING","READY_TO_CONFIRM").contains(q.state) || q.answerStartedAt == null || q.answerExpiresAt == null || OffsetDateTime.now().isBefore(q.answerExpiresAt)) return detail(userId,id);
        int updated=jdbc.sql("UPDATE ai_mock_interview_questions SET state='SKIPPED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state IN ('OPEN','TRANSCRIBING','READY_TO_CONFIRM')").param("id",questionId).update();
        if(updated>0) advanceNext(userId,id,q,questionLimit(session(userId,id)));
        return detail(userId,id);
    }

    Optional<NextPreview> nextPreview(String userId,String id,String questionId) {
        SessionRow s=session(userId,id);
        QuestionRow q=question(id,questionId);
        if(!"RUNNING".equals(s.status) || !s.expiresAt.isAfter(OffsetDateTime.now()) || q.answerStartedAt==null) return Optional.empty();
        return jdbc.sql("SELECT question_text,sort_order,knowledge_source FROM ai_mock_prepared_questions WHERE ai_mock_interview_id=:session AND sort_order=:order")
            .param("session",id).param("order",q.sortOrder+1)
            .query((rs,row)->{ KnowledgeService.Source source=source(rs.getString(3)); return new NextPreview(rs.getString(1),rs.getInt(2),source==null?null:source.title(),source==null?null:source.location()); }).optional();
    }

    @Transactional AnswerStart startAnswer(String userId, String id, String questionId) {
        int started=jdbc.sql("UPDATE ai_mock_interview_questions SET answer_started_at=COALESCE(answer_started_at,CURRENT_TIMESTAMP),answer_expires_at=COALESCE(answer_expires_at,CURRENT_TIMESTAMP + INTERVAL '5' MINUTE),updated_at=CURRENT_TIMESTAMP WHERE id=:question AND ai_mock_interview_id=:session AND state='OPEN' AND EXISTS (SELECT 1 FROM ai_mock_interviews WHERE id=:session AND user_id=:user AND status='RUNNING' AND expires_at>CURRENT_TIMESTAMP) AND NOT EXISTS (SELECT 1 FROM ai_mock_audio_uploads WHERE question_id=:question AND status='UPLOADING')")
            .param("question",questionId).param("session",id).param("user",userId).update();
        if(started==0) {
            if (!"RUNNING".equals(session(userId,id).status)) throw new IllegalStateException("模拟已结束或超时，无法继续处理。");
            QuestionRow q=question(userId,id,questionId);
            if(activeUpload(userId,id,questionId)!=null) throw new IllegalArgumentException("录音正在上传，请继续或放弃后重新录音。");
            if (!"OPEN".equals(q.state)) throw new IllegalArgumentException("当前题目不能开始回答。");
        }
        OffsetDateTime expires=jdbc.sql("SELECT answer_expires_at FROM ai_mock_interview_questions WHERE id=:question AND ai_mock_interview_id=:session").param("question",questionId).param("session",id).query(OffsetDateTime.class).single();
        return new AnswerStart(questionId,expires);
    }
    @Transactional Session skipAnswer(String userId, String id, String questionId) {
        if (!"RUNNING".equals(session(userId,id).status)) return detail(userId,id);
        lockRunning(userId,id); QuestionRow q=question(userId,id,questionId);
        if(activeUpload(userId,id,questionId)!=null) throw new IllegalArgumentException("录音正在上传，请继续或放弃后操作。");
        if ("ANSWERED".equals(q.state)) return detail(userId,id);
        if (!"OPEN".equals(q.state)) throw new IllegalArgumentException("当前题目不能跳过。 ");
        answer(userId,id,q,"",false);
        return detail(userId,id);
    }
    Session audio(String userId, String id, String questionId, MultipartFile file) {
        long preparationStarted=System.nanoTime();
        SessionRow interview=session(userId,id);
        if (!"RUNNING".equals(interview.status)) return detail(userId,id);
        QuestionRow q = question(id,questionId);
        if(activeUpload(userId,id,questionId)!=null) throw new IllegalArgumentException("录音正在上传，请继续或放弃后重新录音。");
        if (Set.of("TRANSCRIBING", "ANSWERED").contains(q.state)) return detail(userId,id);
        if (!"OPEN".equals(q.state)) throw new IllegalArgumentException("当前题目不能录音。");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("录音为空，请重新录音。");
        byte[] bytes; try { bytes=file.getBytes(); } catch(Exception e) { throw new IllegalArgumentException("读取录音失败，请重试。"); }
        String type = validateAudio(bytes);
        String asset = UUID.randomUUID().toString(), path="ai-mock/"+userId+"/"+asset+extension(type);
        log.info("ai_mock_timing stage=audio_direct_prepare sessionId={} questionId={} elapsed_ms={}",id,questionId,(System.nanoTime()-preparationStarted)/1_000_000);
        boolean stored=false;
        try {
            // ponytail: remote Storage runs outside the session lock; the short commit rechecks ownership and state.
            long storageStarted=System.nanoTime();
            try { storage.upload(path,type,bytes); }
            finally { log.info("ai_mock_timing stage=audio_direct_storage_upload sessionId={} questionId={} bytes={} elapsed_ms={}",id,questionId,bytes.length,(System.nanoTime()-storageStarted)/1_000_000); }
            boolean[] claimed={false};
            long commitStarted=System.nanoTime();
            transaction.executeWithoutResult(status -> {
                if (!"RUNNING".equals(session(userId,id).status)) return;
                lockRunning(userId,id);
                QuestionRow current=question(id,questionId);
                if(activeUpload(userId,id,questionId)!=null) throw new IllegalArgumentException("录音正在上传，请继续或放弃后重新录音。");
                if (Set.of("TRANSCRIBING", "ANSWERED").contains(current.state)) return;
                if (!"OPEN".equals(current.state)) throw new IllegalArgumentException("当前题目不能录音。");
                if(jdbc.sql("UPDATE ai_mock_interview_questions SET state='TRANSCRIBING',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state='OPEN'").param("id",questionId).update()==0) return;
                jdbc.sql("INSERT INTO ai_mock_audio_assets(id,user_id,ai_mock_interview_id,question_id,original_filename,content_type,size_bytes,object_path,status,transcript) VALUES(:id,:user,:session,:question,:name,:type,:size,:path,'TRANSCRIBING','')").param("id",asset).param("user",userId).param("session",id).param("question",questionId).param("name",safeName(file.getOriginalFilename())).param("type",type).param("size",bytes.length).param("path",path).update();
                tasks.enqueue(userId,"AI_AUDIO",id,asset);
                acceptAudio(userId,id,current,questionLimit(interview));
                claimed[0]=true;
            });
            log.info("ai_mock_timing stage=audio_direct_commit sessionId={} questionId={} elapsed_ms={}",id,questionId,(System.nanoTime()-commitStarted)/1_000_000);
            stored=claimed[0];
            long detailStarted=System.nanoTime();
            try { return detail(userId,id); }
            finally { log.info("ai_mock_timing stage=audio_direct_detail sessionId={} questionId={} elapsed_ms={}",id,questionId,(System.nanoTime()-detailStarted)/1_000_000); }
        } finally {
            if(!stored) try { storage.delete(path); } catch (RuntimeException ignored) { }
        }
    }
    @Transactional Session confirm(String userId,String id,String questionId,ConfirmRequest request) {
        if (!"RUNNING".equals(session(userId,id).status)) return detail(userId,id);
        lockRunning(userId,id); QuestionRow q=question(userId,id,questionId); if(activeUpload(userId,id,questionId)!=null) throw new IllegalArgumentException("录音正在上传，请继续或放弃后操作。"); if ("ANSWERED".equals(q.state)) return detail(userId,id); if (!Set.of("OPEN","READY_TO_CONFIRM").contains(q.state)) throw new IllegalArgumentException("当前题目回答时间已结束，已进入下一题。");
        answer(userId,id,q,limited(required(request.answerText(),"确认文本"),"确认文本",MAX_TRANSCRIPT_CHARS),true);
        return detail(userId,id);
    }
    @Transactional Session finish(String userId,String id) {
        session(userId,id); lock(userId,id);
        SessionRow s=session(userId,id); if (s.status.equals("FINISHED")) return detail(userId,id);
        if(jdbc.sql("SELECT COUNT(*) FROM ai_mock_audio_uploads WHERE user_id=:user AND ai_mock_interview_id=:id AND status='UPLOADING'").param("user",userId).param("id",id).query(Integer.class).single()>0) throw new IllegalStateException("录音正在上传，请等待完成后再保存。");
        // ponytail: finish snapshots confirmed answers now; unfinished audio is excluded when its worker is cancelled.
        List<QuestionRow> rows=questions(userId,id).stream().filter(q -> "ANSWERED".equals(q.state)).toList();
        var formal=interviews.createFromMock(userId,new InterviewRequest(s.company,s.role,s.round,OffsetDateTime.now(),s.packageId,"PENDING_REVIEW","UNKNOWN","AI 模拟面试记录，仅含用户确认的转写文本。"),rows.stream().map(q->new QuestionRequest(q.text,q.answer,q.answer.isBlank()?"UNANSWERED":"UNCERTAIN")).toList());
        jdbc.sql("UPDATE ai_mock_interviews SET status='FINISHED',final_interview_id=:final,finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user").param("final",formal.interview().id()).param("id",id).param("user",userId).update(); tasks.cancelForResource(userId,id); return detail(userId,id);
    }
    @Transactional void delete(String userId,String id) { lock(userId,id); SessionRow s=session(userId,id); tasks.deleteForResource(userId,id); for(UploadRow upload:uploads(id)) clearParts(upload.id); for (AudioRow a:audioRows(id)) try { storage.delete(a.path); } catch(RuntimeException ignored) {} if(jdbc.sql("DELETE FROM ai_mock_interviews WHERE id=:id AND user_id=:user").param("id",id).param("user",userId).update()==0) throw notFound(); }
    ResponseEntity<byte[]> content(String userId,String audioId) { AudioRow a=audio(userId,audioId); if("DELETED".equals(a.status)) throw notFound(); return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.type)).body(storage.download(a.path)); }
    @Transactional void deleteAudio(String userId,String audioId) {
        AudioRow a=audio(userId,audioId);
        String sessionId=jdbc.sql("SELECT ai_mock_interview_id FROM ai_mock_audio_assets WHERE id=:id").param("id",audioId).query(String.class).single();
        lock(userId,sessionId);
        SessionRow s=session(userId,sessionId);
        jdbc.sql("UPDATE ai_mock_tasks SET status='COMPLETED',worker_token=NULL,locked_at=NULL,error='',updated_at=CURRENT_TIMESTAMP WHERE user_id=:user AND resource_id=:session AND related_id=:audio AND task_type='AI_AUDIO'")
            .param("user",userId).param("session",sessionId).param("audio",audioId).update();
        storage.delete(a.path);
        jdbc.sql("DELETE FROM ai_mock_audio_assets WHERE id=:id AND user_id=:user").param("id",audioId).param("user",userId).update();
        if("RUNNING".equals(s.status)) jdbc.sql("UPDATE ai_mock_interview_questions SET state='OPEN',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND confirmed_answer_text='' AND NOT EXISTS (SELECT 1 FROM ai_mock_interview_questions later WHERE later.ai_mock_interview_id=:session AND later.sort_order>(SELECT sort_order FROM ai_mock_interview_questions WHERE id=:id))").param("id",a.questionId).param("session",sessionId).update();
    }

    private void answer(String userId,String id,QuestionRow q,String text,boolean needsFeedback) {
        int updated=jdbc.sql("UPDATE ai_mock_interview_questions SET confirmed_answer_text=:answer,state='ANSWERED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state IN ('OPEN','TRANSCRIBING','READY_TO_CONFIRM')")
            .param("answer",text).param("id",q.id).update();
        if(updated>0) {
            SessionRow s=session(userId,id);
            advanceNext(userId,id,q,questionLimit(s));
            if(needsFeedback) tasks.enqueue(userId,"AI_FEEDBACK",id,q.id);
        }
    }

    private void acceptAudio(String userId,String id,QuestionRow q,int limit) {
        if(jdbc.sql("UPDATE ai_mock_interview_questions SET state='ANSWERED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state='TRANSCRIBING'")
            .param("id",q.id).update()>0) advanceNext(userId,id,q,limit);
    }

    private void addQuestion(String userId,String id,int order) { addQuestion(userId,id,order,false); }

    private void addQuestion(String userId,String id,int order,boolean prepared) {
        long setupStarted=System.nanoTime();
        SessionRow s=session(userId,id);
        if(!"RUNNING".equals(s.status)||order>=questionLimit(s)||(s.plan==null && !incremental(s))) return;
        if(hasQuestion(id,order) || (prepared && hasPrepared(id,order))) return;
        boolean strictProject=!legacy(s);
        log.info("ai_mock_timing stage=question_setup taskId={} sessionId={} order={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,order,(System.nanoTime()-setupStarted)/1_000_000);
        long contextStarted=System.nanoTime();
        tasks.check();
        JsonNode materials=snapshot(userId,s);
        List<QuestionHistory> questionHistory=history(userId,id);
        PlanItem slot=incremental(s)?null:questionAgent.deserialize(s.plan,legacy(s)).get(order);
        JsonNode context=incremental(s)?knowledgeContext(userId,s,order,materials,questionHistory):null;
        KnowledgeService.Source source=context==null?knowledgeSource(userId,s,s.role+" "+materials.path("jd").asText()+" "+slot.competency()+" "+slot.technology()+" "+slot.angle()):source(context.path("source").toString());
        log.info("ai_mock_timing stage=question_context taskId={} sessionId={} order={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,order,(System.nanoTime()-contextStarted)/1_000_000);
        QuestionDraft draft=context==null?questionAgent.generate(materials,slot,questionHistory,strictProject,source==null?null:knowledgeText(source))
            :questionAgent.generateKnowledge(materials,context.path("target"),questionHistory,context.path("recentQuestions"),context.path("avoidRecent").asBoolean(),knowledgeText(source));
        long commitStarted=System.nanoTime();
        try {
            tasks.write(() -> {
                if(hasQuestion(id,order) || (prepared && hasPrepared(id,order))) return;
                String error=context==null?qualityError(draft,slot,history(id)):knowledgeQualityError(draft,context.path("target"),materials,history(id));
                if (error!=null) { log.warn("AI VOICE_QUESTION rejected during commit reason={}", error); throw SimulationContract.retryableInvalid(); }
                String previousState=prepared?jdbc.sql("SELECT state FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:session AND sort_order=:order")
                    .param("session",id).param("order",order-1).query(String.class).single():"ANSWERED";
                boolean publish=Set.of("ANSWERED","SKIPPED").contains(previousState);
                if(publish) {
                    if(insertQuestion(id,order,draft,source)) prepareNext(userId,id,order,questionLimit(s));
                } else savePrepared(id,order,draft,source);
                if(prepared) log.info("ai_mock_timing stage=question_prepared taskId={} sessionId={} order={} published={}",org.slf4j.MDC.get("taskId"),id,order,publish);
            });
        } finally { log.info("ai_mock_timing stage=question_commit taskId={} sessionId={} order={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),id,order,(System.nanoTime()-commitStarted)/1_000_000); }
    }

    private boolean insertQuestion(String id,int order,QuestionDraft draft,KnowledgeService.Source source) {
        return jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,question_type,competency,project_name,technology,state,sort_order,knowledge_source) SELECT :id,:session,:text,:type,:competency,:project,:technology,:state,:order,:source WHERE NOT EXISTS (SELECT 1 FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:session AND sort_order=:order)")
            .param("id",UUID.randomUUID().toString()).param("session",id).param("text",draft.questionText()).param("type",draft.type()).param("competency",draft.competency()).param("project",draft.projectName()).param("technology",draft.technology()).param("state","OPEN").param("order",order).param("source",sourceJson(source)).update()==1;
    }

    private boolean hasPrepared(String id,int order) { return jdbc.sql("SELECT COUNT(*) FROM ai_mock_prepared_questions WHERE ai_mock_interview_id=:session AND sort_order=:order").param("session",id).param("order",order).query(Integer.class).single()>0; }

    private void savePrepared(String id,int order,QuestionDraft draft,KnowledgeService.Source source) {
        jdbc.sql("INSERT INTO ai_mock_prepared_questions(ai_mock_interview_id,sort_order,question_text,question_type,competency,project_name,technology,knowledge_source) SELECT :session,:order,:text,:type,:competency,:project,:technology,:source WHERE NOT EXISTS (SELECT 1 FROM ai_mock_prepared_questions WHERE ai_mock_interview_id=:session AND sort_order=:order)")
            .param("session",id).param("order",order).param("text",draft.questionText()).param("type",draft.type()).param("competency",draft.competency()).param("project",draft.projectName()).param("technology",draft.technology()).param("source",sourceJson(source)).update();
    }

    private void prepareNext(String userId,String id,int order,int limit) {
        if(order+1<limit) {
            String questionId=jdbc.sql("SELECT id FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:session AND sort_order=:order")
                .param("session",id).param("order",order).query(String.class).single();
            tasks.enqueue(userId,"AI_PREPARE_NEXT",id,questionId);
        }
    }

    private boolean openPrepared(String userId,String id,int order,int limit) {
        if(order>=limit) return false;
        int updated=jdbc.sql("INSERT INTO ai_mock_interview_questions(id,ai_mock_interview_id,question_text,question_type,competency,project_name,technology,state,sort_order,knowledge_source) SELECT :id,p.ai_mock_interview_id,p.question_text,p.question_type,p.competency,p.project_name,p.technology,'OPEN',p.sort_order,p.knowledge_source FROM ai_mock_prepared_questions p WHERE p.ai_mock_interview_id=:session AND p.sort_order=:order AND NOT EXISTS (SELECT 1 FROM ai_mock_interview_questions q WHERE q.ai_mock_interview_id=:session AND q.sort_order=:order)")
            .param("id",UUID.randomUUID().toString()).param("session",id).param("order",order).update();
        if(updated>0) {
            jdbc.sql("DELETE FROM ai_mock_prepared_questions WHERE ai_mock_interview_id=:session AND sort_order=:order").param("session",id).param("order",order).update();
            log.info("ai_mock_timing stage=question_prepared_release taskId={} sessionId={} order={}",org.slf4j.MDC.get("taskId"),id,order);
            prepareNext(userId,id,order,limit);
        }
        return updated>0;
    }

    private void advanceNext(String userId,String id,QuestionRow q,int limit) {
        if(q.sortOrder+1>=limit) return;
        if(!openPrepared(userId,id,q.sortOrder+1,limit)) tasks.enqueue(userId,"AI_NEXT",id,q.id);
    }

    private boolean hasQuestion(String id,int order) { return jdbc.sql("SELECT COUNT(*) FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id AND sort_order=:order").param("id",id).param("order",order).query(Integer.class).single()>0; }
    private List<QuestionHistory> history(String user,String id) { session(user,id); return history(id); }
    private List<QuestionHistory> history(String id) { return questions(id).stream().map(q->new QuestionHistory(q.text,empty(q.type),empty(q.competency),empty(q.projectName),empty(q.technology))).toList(); }

    private JsonNode knowledgeContext(String user,SessionRow s,int order,JsonNode materials,List<QuestionHistory> history) {
        List<String> documents=documentIds(s);
        knowledge.requireDocuments(user,documents);
        String key=Integer.toString(order);
        Random random=new Random(UUID.fromString(s.id).getMostSignificantBits() ^ UUID.fromString(s.id).getLeastSignificantBits() ^ order);
        String type=order<5?"FUNDAMENTAL":order<9?"PROJECT":random.nextBoolean()?"SCENARIO":"BEHAVIORAL";
        // ponytail: rank segments before the short package lock; only source reservation needs the lock.
        long rankingStarted=System.nanoTime();
        List<Map.Entry<KnowledgeService.Source,Integer>> ranked=readContexts(s.knowledgeContexts).has(key)?List.of()
            :knowledge.rankedSources(user,documents,s.role+" "+materials.path("jd").asText()+" "+type);
        if(!ranked.isEmpty()) log.info("ai_mock_timing stage=question_rank taskId={} sessionId={} order={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),s.id,order,(System.nanoTime()-rankingStarted)/1_000_000);
        JsonNode[] frozen={null};
        tasks.write(() -> {
            // ponytail: one short package lock makes concurrent sessions see each other's source reservations.
            jdbc.sql("SELECT id FROM interview_packages WHERE id=:id AND user_id=:user FOR UPDATE").param("id",s.packageId).param("user",user).query(String.class).single();
            var contexts=(com.fasterxml.jackson.databind.node.ObjectNode)readContexts(session(user,s.id).knowledgeContexts);
            if (contexts.has(key)) { frozen[0]=contexts.path(key); return; }
            var peers=jdbc.sql("SELECT v.id,v.knowledge_contexts FROM ai_mock_interviews v WHERE v.user_id=:user AND v.interview_package_id=:package AND v.source_mode='KNOWLEDGE' AND v.id<>:id AND EXISTS (SELECT 1 FROM knowledge_documents d WHERE d.user_id=:user AND d.id IN (:documents) AND (',' || v.knowledge_document_ids || ',') LIKE ('%,' || d.id || ',%')) ORDER BY v.created_at DESC,v.id DESC LIMIT 3")
                .param("user",user).param("package",s.packageId).param("id",s.id).param("documents",documentIds(s))
                .query((rs,row)->Map.entry(rs.getString(1),readContexts(rs.getString(2)))).list();
            var recent=json.createArrayNode();
            Map<String,Integer> uses=new HashMap<>();
            Map<String,Integer> projectUses=new HashMap<>();
            Set<String> recorded=new HashSet<>();
            if (!peers.isEmpty()) {
                var rows=jdbc.sql("SELECT ai_mock_interview_id,sort_order,question_text,question_type,competency,project_name,technology,knowledge_source FROM ai_mock_interview_questions WHERE ai_mock_interview_id IN (:ids) UNION ALL SELECT ai_mock_interview_id,sort_order,question_text,question_type,competency,project_name,technology,knowledge_source FROM ai_mock_prepared_questions WHERE ai_mock_interview_id IN (:ids)")
                    .param("ids",peers.stream().map(Map.Entry::getKey).toList())
                    .query((rs,row)->new RecentQuestion(rs.getString(1),rs.getInt(2),new QuestionHistory(rs.getString(3),empty(rs.getString(4)),empty(rs.getString(5)),empty(rs.getString(6)),empty(rs.getString(7))),source(rs.getString(8)))).list();
                for (RecentQuestion row:rows) {
                    if (!recorded.add(row.sessionId+":"+row.order)) continue;
                    if (recent.size()<30) recent.add(json.valueToTree(row.question));
                    if (row.source!=null) uses.merge(row.source.id(),row.order==0?2:1,Integer::sum);
                    projectUses.merge(row.question.projectName(),1,Integer::sum);
                }
            }
            for (var peer:peers) peer.getValue().fields().forEachRemaining(item -> {
                if (!recorded.contains(peer.getKey()+":"+item.getKey())) uses.merge(item.getValue().path("source").path("id").asText(),item.getKey().equals("0")?2:1,Integer::sum);
            });
            Set<String> used=new HashSet<>();
            contexts.elements().forEachRemaining(item->used.add(item.path("source").path("id").asText()));
            String project="";
            if (type.equals("PROJECT")) {
                String previous=history.isEmpty()?"":history.getLast().projectName();
                LinkedHashSet<String> anchors=new LinkedHashSet<>();
                materials.path("cards").forEach(card->{ String name=card.path("projectName").asText(); if (!name.isBlank() && !name.equals("待补充")) anchors.add(name); });
                // ponytail: resume-only projects must have an explicit experience heading; ambiguous headers require better materials.
                if (anchors.isEmpty()) materials.path("experienceAnchors").forEach(anchor->{ String name=anchor.asText(); if (!name.equals(materials.path("company").asText()) && !name.equals(materials.path("role").asText()) && materials.path("resume").asText().contains(name) && name.matches(".*(项目|系统|平台|实习|公司).*")) anchors.add(name); });
                var choices=anchors.stream().filter(name->!name.equals(previous)).toList();
                if (choices.isEmpty()) throw new IllegalArgumentException("缺少可交替考察的真实项目或实习经历，请补充面试包资料。");
                int least=choices.stream().mapToInt(name->projectUses.getOrDefault(name,0)).min().orElse(0);
                var preferred=choices.stream().filter(name->projectUses.getOrDefault(name,0)==least).toList();
                project=preferred.get(random.nextInt(preferred.size()));
            }
            String previousSource=order==0?"":contexts.path(Integer.toString(order-1)).path("source").path("id").asText();
            KnowledgeService.Source source=knowledge.variedSource(ranked,used,previousSource,uses,random);
            var angles=new ArrayList<>(List.of("机制与因果","边界条件","设计取舍","错误定位","验证方法"));
            String previousAngle=order==0?"":contexts.path(Integer.toString(order-1)).path("target").path("angle").asText();
            angles.remove(previousAngle);
            Map<String,Integer> angleUses=new HashMap<>();
            for (var peer:peers) peer.getValue().elements().forEachRemaining(item->{ if(item.path("source").path("id").asText().equals(source.id())) angleUses.merge(item.path("target").path("angle").asText(),1,Integer::sum); });
            int leastAngle=angles.stream().mapToInt(angle->angleUses.getOrDefault(angle,0)).min().orElse(0);
            var preferredAngles=angles.stream().filter(angle->angleUses.getOrDefault(angle,0)==leastAngle).toList();
            var target=json.createObjectNode().put("order",order+1).put("type",type).put("projectName",project).put("angle",preferredAngles.get(random.nextInt(preferredAngles.size())));
            var context=json.createObjectNode(); context.set("target",target); context.set("source",json.valueToTree(source)); context.set("recentQuestions",recent);
            // Hard recent-history exclusion only for a fresh source; exhausted corpora retain the soft prompt preference.
            context.put("avoidRecent",uses.getOrDefault(source.id(),0)==0 && !recent.isEmpty());
            contexts.set(key,context);
            jdbc.sql("UPDATE ai_mock_interviews SET knowledge_contexts=:contexts WHERE id=:id AND user_id=:user").param("contexts",contexts.toString()).param("id",s.id).param("user",user).update();
            frozen[0]=context;
        });
        return frozen[0];
    }
    private JsonNode readContexts(String text) {
        if (text==null) return json.createObjectNode();
        try { JsonNode result=json.readTree(text); if (!result.isObject()) throw new IllegalArgumentException(); return result; }
        catch(Exception error) { throw new IllegalStateException("知识库选题依据无效。",error); }
    }
    private KnowledgeService.Source knowledgeSource(String user,SessionRow session,String query) {
        if (!session.sourceMode.equals("KNOWLEDGE")) return null;
        List<String> documents=documentIds(session);
        knowledge.requireDocuments(user,documents);
        Set<String> used=new HashSet<>();
        for (String value:jdbc.sql("SELECT knowledge_source FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id UNION ALL SELECT knowledge_source FROM ai_mock_prepared_questions WHERE ai_mock_interview_id=:id")
            .param("id",session.id).query(String.class).list()) {
            KnowledgeService.Source previous=source(value);
            if(previous!=null) used.add(previous.id());
        }
        try { return knowledge.retrieve(user,documents,query,used); }
        catch (IllegalArgumentException error) {
            // ponytail: reuse a source only after every selected segment has been used once.
            return knowledge.retrieve(user,documents,query,Set.of());
        }
    }
    private static List<String> documentIds(SessionRow session) { return session.documentIds.isBlank()?List.of():List.of(session.documentIds.split(",")); }
    private static String clip(String value,int maximum) { return value.length()<=maximum?value:value.substring(0,maximum); }
    private static String knowledgeText(KnowledgeService.Source source) {
        String text="文档："+source.title()+"；位置："+source.location()+"\n内容："+source.text();
        if(!source.answer().isBlank()) text+="\n参考答案（仅供反馈，不是用户经历）："+source.answer();
        if(!source.reminder().isBlank()) text+="\n提醒："+source.reminder();
        return clip(text,6000);
    }
    private String sourceJson(KnowledgeService.Source source) {
        if(source==null) return "";
        try { return json.writeValueAsString(source); } catch(Exception error) { throw new IllegalStateException("无法保存知识库来源。",error); }
    }
    private KnowledgeService.Source source(String value) {
        if(value==null||value.isBlank()) return null;
        try { return json.readValue(value,KnowledgeService.Source.class); } catch(Exception error) { throw new IllegalStateException("知识库来源快照无效。",error); }
    }
    private static String empty(String value) { return value==null?"":value; }
    private JsonNode snapshot(String user,SessionRow s) {
        if (!legacy(s) && s.snapshot==null) throw SimulationContract.invalid();
        return materials.read(s.snapshot,user,s.packageId);
    }
    private static boolean legacy(SessionRow s) { return "LEGACY".equals(s.generationVersion); }
    private static boolean incremental(SessionRow s) { return KNOWLEDGE_VERSION.equals(s.generationVersion) && "KNOWLEDGE".equals(s.sourceMode); }
    private void lock(String user,String id) { jdbc.sql("SELECT id FROM ai_mock_interviews WHERE id=:id AND user_id=:user FOR UPDATE").param("id",id).param("user",user).query(String.class).optional().orElseThrow(AiMockInterviewService::notFound); }
    private void lockRunning(String user,String id) {
        lock(user,id);
        SessionRow s=session(user,id);
        if (!"RUNNING".equals(s.status)) throw new IllegalStateException("模拟已结束或超时，无法继续处理。");
    }
    public void publishPreparation(String user,String preparationId) {
        var preparation=preparations.get(user,preparationId);
        for (String id:jdbc.sql("SELECT id FROM ai_mock_interviews WHERE user_id=:user AND preparation_id=:preparation AND status='RUNNING' AND question_plan IS NULL AND expires_at>CURRENT_TIMESTAMP")
            .param("user",user).param("preparation",preparationId).query(String.class).list()) applyPreparation(user,id,preparation);
    }

    private boolean applyPreparation(String user,String id,InterviewPackagePreparationService.Preparation preparation) {
        if (preparation.generatedResult()==null) return false;
        long started=System.nanoTime();
        List<PlanItem> planned=preparations.planned(preparation,id);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (jdbc.sql("SELECT id FROM ai_mock_interviews WHERE id=:id AND user_id=:user AND preparation_id=:preparation AND material_snapshot=:snapshot AND question_plan IS NULL AND status='RUNNING' AND expires_at>CURRENT_TIMESTAMP FOR UPDATE")
                .param("id",id).param("user",user).param("preparation",preparation.id()).param("snapshot",preparation.materialSnapshot()).query(String.class).optional().isEmpty()) return false;
            jdbc.sql("UPDATE ai_mock_interviews SET question_plan=:plan,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user")
                .param("plan",questionAgent.serialize(planned)).param("id",id).param("user",user).update();
            int index=jdbc.sql("SELECT current_question_index FROM ai_mock_interviews WHERE id=:id").param("id",id).query(Integer.class).single();
            if (index>=0) tasks.enqueue(user,"AI_FIRST",id,null);
            log.info("ai_mock_timing stage=package_plan_applied sessionId={} preparationId={} elapsed_ms={}",id,preparation.id(),(System.nanoTime()-started)/1_000_000);
            return true;
        }));
    }

    private Session detail(String user,String id) {
        SessionRow s=session(user,id);
        if (s.plan==null && s.preparationId!=null && "RUNNING".equals(s.status)
            && applyPreparation(user,id,preparations.get(user,s.preparationId))) s=session(user,id);
        List<QuestionRow> q=questions(id);
        var task=s.plan==null && s.preparationId!=null && "RUNNING".equals(s.status)?preparations.task(user,s.preparationId):tasks.latestVoice(user,id);
        return new Session(s.id,s.company,s.role,s.round,s.status,s.sourceMode,s.startedAt,s.finalId,questionLimit(s),q.stream().filter(x->Set.of("OPEN","TRANSCRIBING","READY_TO_CONFIRM").contains(x.state)).findFirst().map(x->apiQuestion(x,user)).orElse(null),task,s.generationVersion,s.currentIndex<0,(int)q.stream().filter(x->Set.of("ANSWERED","SKIPPED").contains(x.state)).count());
    }
    private Question apiQuestion(QuestionRow q,String user) { KnowledgeService.Source source=source(q.knowledgeSource); return new Question(q.id,q.text,legacyType(q),q.competency,q.answer,q.state,q.sortOrder,q.answerExpiresAt,latestAudio(user,q.id),source==null?null:source.title(),source==null?null:source.location()); }
    private Audio latestAudio(String user,String question) { return jdbc.sql("SELECT id,status,transcript,transcript_error,feedback,duration_ms FROM ai_mock_audio_assets WHERE user_id=:user AND question_id=:q ORDER BY created_at DESC LIMIT 1").param("user",user).param("q",question).query((rs,row)->new Audio(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),(Long)rs.getObject(6))).optional().orElse(null); }
    private SessionRow session(String user,String id) { tasks.expireVoice(user,id); return jdbc.sql("SELECT id,interview_package_id,company,role,interview_round,status,started_at,expires_at,final_interview_id,question_plan,material_snapshot,generation_version,source_mode,knowledge_document_ids,preparation_id,knowledge_contexts,current_question_index FROM ai_mock_interviews WHERE id=:id AND user_id=:user").param("id",id).param("user",user).query((rs,row)->new SessionRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,OffsetDateTime.class),rs.getObject(8,OffsetDateTime.class),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),rs.getInt(17))).optional().orElseThrow(AiMockInterviewService::notFound); }
    private QuestionRow question(String user,String session,String id) { session(user,session); return question(session,id); }
    private QuestionRow question(String session,String id) { return questions(session).stream().filter(q->q.id.equals(id)).findFirst().orElseThrow(AiMockInterviewService::notFound); }
    private QuestionRow lockedQuestion(String user,String session,String id) { return jdbc.sql("SELECT id,question_text,confirmed_answer_text,state,sort_order,answer_started_at,answer_expires_at,question_type,competency,project_name,technology,ai_feedback,knowledge_source FROM ai_mock_interview_questions WHERE id=:question AND ai_mock_interview_id=:session AND EXISTS (SELECT 1 FROM ai_mock_interviews WHERE id=:session AND user_id=:user AND status='RUNNING' AND expires_at>CURRENT_TIMESTAMP) FOR UPDATE").param("question",id).param("session",session).param("user",user).query((rs,row)->new QuestionRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13))).optional().orElseThrow(AiMockInterviewService::notFound); }
    private List<QuestionRow> questions(String user,String id) { session(user,id); return questions(id); }
    private List<QuestionRow> questions(String id) { return jdbc.sql("SELECT id,question_text,confirmed_answer_text,state,sort_order,answer_started_at,answer_expires_at,question_type,competency,project_name,technology,ai_feedback,knowledge_source FROM ai_mock_interview_questions WHERE ai_mock_interview_id=:id ORDER BY sort_order").param("id",id).query((rs,row)->new QuestionRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13))).list(); }
    private AudioRow audio(String user,String id) { return jdbc.sql("SELECT id,question_id,object_path,content_type,status,transcript FROM ai_mock_audio_assets WHERE id=:id AND user_id=:user").param("id",id).param("user",user).query((rs,row)->new AudioRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6))).optional().orElseThrow(AiMockInterviewService::notFound); }
    private List<AudioRow> audioRows(String id) { return jdbc.sql("SELECT id,question_id,object_path,content_type,status,transcript FROM ai_mock_audio_assets WHERE ai_mock_interview_id=:id AND status<>'DELETED'").param("id",id).query((rs,row)->new AudioRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6))).list(); }
    private UploadRow upload(String user,String session,String question,String id) { return jdbc.sql("SELECT id,user_id,ai_mock_interview_id,question_id,content_type,total_bytes,total_parts,sha256,status,expires_at,completed_asset_id FROM ai_mock_audio_uploads WHERE id=:id AND user_id=:user AND ai_mock_interview_id=:session AND question_id=:question").param("id",id).param("user",user).param("session",session).param("question",question).query((rs,row)->uploadRow(rs)).optional().orElseThrow(AiMockInterviewService::notFound); }
    private UploadRow uploadById(String user,String session,String id) { return jdbc.sql("SELECT id,user_id,ai_mock_interview_id,question_id,content_type,total_bytes,total_parts,sha256,status,expires_at,completed_asset_id FROM ai_mock_audio_uploads WHERE id=:id AND user_id=:user AND ai_mock_interview_id=:session").param("id",id).param("user",user).param("session",session).query((rs,row)->uploadRow(rs)).optional().orElseThrow(AiMockInterviewService::notFound); }
    private UploadRow activeUpload(String user,String session,String question) { return jdbc.sql("SELECT id,user_id,ai_mock_interview_id,question_id,content_type,total_bytes,total_parts,sha256,status,expires_at,completed_asset_id FROM ai_mock_audio_uploads WHERE user_id=:user AND ai_mock_interview_id=:session AND question_id=:question AND status='UPLOADING' ORDER BY created_at DESC LIMIT 1").param("user",user).param("session",session).param("question",question).query((rs,row)->uploadRow(rs)).optional().orElse(null); }
    private List<UploadRow> uploads(String session) { return jdbc.sql("SELECT id,user_id,ai_mock_interview_id,question_id,content_type,total_bytes,total_parts,sha256,status,expires_at,completed_asset_id FROM ai_mock_audio_uploads WHERE ai_mock_interview_id=:session AND status IN ('UPLOADING','COMPLETED')").param("session",session).query((rs,row)->uploadRow(rs)).list(); }
    private static UploadRow uploadRow(ResultSet rs) throws java.sql.SQLException { return new UploadRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getInt(7),rs.getString(8),rs.getString(9),rs.getObject(10,OffsetDateTime.class),rs.getString(11)); }
    private AudioUpload apiUpload(UploadRow upload) { return new AudioUpload(upload.id,AUDIO_CHUNK_BYTES,upload.totalBytes,upload.totalParts,parts(upload.id).stream().map(PartRow::partNo).toList(),upload.status,upload.expiresAt); }
    private PartRow part(String upload,int number) { return jdbc.sql("SELECT part_no,size_bytes,sha256,object_path FROM ai_mock_audio_upload_parts WHERE upload_id=:upload AND part_no=:part").param("upload",upload).param("part",number).query((rs,row)->new PartRow(rs.getInt(1),rs.getInt(2),rs.getString(3),rs.getString(4))).optional().orElse(null); }
    private List<PartRow> parts(String upload) { return jdbc.sql("SELECT part_no,size_bytes,sha256,object_path FROM ai_mock_audio_upload_parts WHERE upload_id=:upload ORDER BY part_no").param("upload",upload).query((rs,row)->new PartRow(rs.getInt(1),rs.getInt(2),rs.getString(3),rs.getString(4))).list(); }
    private void clearParts(String upload) { for(PartRow part:parts(upload)) { long deleteStarted=System.nanoTime(); try { storage.delete(part.path); } catch(RuntimeException ignored) {} finally { log.info("ai_mock_timing stage=audio_staging_delete taskId={} uploadId={} partNo={} elapsed_ms={}",org.slf4j.MDC.get("taskId"),upload,part.partNo,(System.nanoTime()-deleteStarted)/1_000_000); } } jdbc.sql("DELETE FROM ai_mock_audio_upload_parts WHERE upload_id=:upload").param("upload",upload).update(); }
    private void expireUpload(UploadRow upload) { clearParts(upload.id); jdbc.sql("UPDATE ai_mock_audio_uploads SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='UPLOADING'").param("id",upload.id).update(); int skipped=jdbc.sql("UPDATE ai_mock_interview_questions SET state='SKIPPED',updated_at=CURRENT_TIMESTAMP WHERE id=:question AND state='OPEN'").param("question",upload.questionId).update(); if(skipped>0) advanceNext(upload.userId,upload.sessionId,question(upload.sessionId,upload.questionId),questionLimit(session(upload.userId,upload.sessionId))); }
    private static void requireUploading(UploadRow upload) { if(!"UPLOADING".equals(upload.status)) throw new IllegalArgumentException("录音上传已结束。"); if(!OffsetDateTime.now().isBefore(upload.expiresAt)) throw new IllegalArgumentException("录音上传已过期，请重新录音。"); }
    private static Range range(String value) { var matcher=java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(value==null?"":value); if(!matcher.matches()) throw new IllegalArgumentException("录音分片范围无效。"); return new Range(Long.parseLong(matcher.group(1)),Long.parseLong(matcher.group(2)),Long.parseLong(matcher.group(3))); }
    private static String sha(String value) { if(value==null||!value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("录音摘要无效。"); return value.toLowerCase(Locale.ROOT); }
    private static String sha(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception error) { throw new IllegalStateException("无法校验录音完整性。",error); } }
    private int questionLimit(SessionRow session) { if (!legacy(session)) { if(session.plan!=null && !incremental(session)) questionAgent.deserialize(session.plan,false); return QUESTION_LIMIT; } return session.plan==null?LEGACY_QUESTION_LIMIT:questionAgent.deserialize(session.plan,true).size(); }
    private static String legacyType(QuestionRow q) { return q.type!=null?q.type:q.sortOrder==0?"FUNDAMENTAL":q.sortOrder==1?"PROJECT":"SCENARIO"; }
    private static String audioType(byte[] b) { if(b.length>12&&b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='A'&&b[10]=='V'&&b[11]=='E')return "audio/wav"; if(b.length>4&&b[0]=='O'&&b[1]=='g'&&b[2]=='g'&&b[3]=='S')return "audio/ogg"; if(b.length>4&&b[0]==0x1a&&b[1]==0x45&&b[2]==(byte)0xdf&&b[3]==(byte)0xa3)return "audio/webm"; if(b.length>12&&b[4]=='f'&&b[5]=='t'&&b[6]=='y'&&b[7]=='p')return "audio/mp4"; return null; }
    static String validateAudio(byte[] bytes) { if(bytes.length==0)throw new IllegalArgumentException("录音为空，请重新录音。"); if(bytes.length>MAX_AUDIO_BYTES)throw new IllegalArgumentException("录音超过 10 MiB，请缩短回答后重新录音。"); String type=audioType(bytes); if(type==null)throw new IllegalArgumentException("仅支持 WebM、Ogg、MP4 或 WAV 音频。"); return type; }
    private static String extension(String t){return t.endsWith("wav")?".wav":t.endsWith("ogg")?".ogg":t.endsWith("mp4")?".mp4":".webm";} private static String feedback(String text,Long duration){return "已确认文本 "+text.length()+" 字；当前转写结果不含词级时间戳，无法可靠计算语速、停顿或重复词。";} private static String safeName(String n){return n==null||n.isBlank()?"answer":n.replaceAll("[^\\p{L}\\p{N}._-]","_");} private static String required(String v,String l){if(v==null||v.trim().isBlank())throw new IllegalArgumentException(l+"不能为空。");return v.trim();} private static String limited(String value,String label,int maximum){if(value.length()>maximum)throw new IllegalArgumentException(label+"过长，请控制在 "+maximum+" 个字符以内。");return value;} private static NoSuchElementException notFound(){return new NoSuchElementException("资源不存在或无权访问。");}
    private record RecentQuestion(String sessionId,int order,QuestionHistory question,KnowledgeService.Source source){}
    private record PackageInfo(String id,String company,String role,String round){} private record SessionRow(String id,String packageId,String company,String role,String round,String status,OffsetDateTime startedAt,OffsetDateTime expiresAt,String finalId,String plan,String snapshot,String generationVersion,String sourceMode,String documentIds,String preparationId,String knowledgeContexts,int currentIndex){} private record QuestionRow(String id,String text,String answer,String state,int sortOrder,OffsetDateTime answerStartedAt,OffsetDateTime answerExpiresAt,String type,String competency,String projectName,String technology,String feedback,String knowledgeSource){} private record AudioRow(String id,String questionId,String path,String type,String status,String transcript){} private record UploadRow(String id,String userId,String sessionId,String questionId,String contentType,long totalBytes,int totalParts,String sha256,String status,OffsetDateTime expiresAt,String completedAssetId){} private record PartRow(int partNo,int sizeBytes,String sha256,String path){} private record Range(long start,long end,long total){}
}

package com.interviewagent.interview;

import static com.interviewagent.interview.InterviewApi.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import com.interviewagent.ai.storage.AiAudioStorage;
import com.interviewagent.ai.storage.AudioTranscriptionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
class InterviewImportService {
    static final long MAX_AUDIO_BYTES = 25L * 1024 * 1024;
    private static final int MAX_TRANSCRIPT_CHARS = 72_000;
    private static final int CHUNK_CHARS = 24_000;
    private final JdbcClient jdbc;
    private final AiAudioStorage storage;
    private final AudioTranscriptionService transcription;
    private final ReviewModelClient model;
    private final InterviewService interviews;
    private final ObjectMapper json;

    InterviewImportService(JdbcClient jdbc, AiAudioStorage storage, AudioTranscriptionService transcription, ReviewModelClient model, InterviewService interviews, ObjectMapper json) {
        this.jdbc = jdbc; this.storage = storage; this.transcription = transcription; this.model = model; this.interviews = interviews; this.json = json;
    }

    InterviewImport upload(String userId, String interviewId, MultipartFile file) {
        if (interviewId != null && !interviewId.isBlank()) interviews.ensureEditableQuestions(userId, interviewId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("录音为空，请选择一段录音后重试。");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (Exception exception) { throw new IllegalArgumentException("读取录音失败，请重新选择文件。"); }
        String type = validateAudio(bytes);
        String id = UUID.randomUUID().toString();
        String path = "interview-imports/" + userId + "/" + id + extension(type);
        storage.uploadImport(path, type, bytes);
        jdbc.sql("INSERT INTO interview_audio_imports (id,user_id,target_interview_id,original_filename,content_type,size_bytes,object_path,status) VALUES (:id,:user,:target,:name,:type,:size,:path,'TRANSCRIBING')")
            .param("id", id).param("user", userId).param("target", interviewId).param("name", safeName(file.getOriginalFilename())).param("type", type).param("size", bytes.length).param("path", path).update();
        try {
            String transcript = limit(transcription.transcribe(userId, bytes, type), "转写文本", MAX_TRANSCRIPT_CHARS);
            jdbc.sql("UPDATE interview_audio_imports SET transcript=:text,status='ANALYZING',error='',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user")
                .param("text", transcript).param("id", id).param("user", userId).update();
        } catch (RuntimeException exception) {
            fail(userId, id, "TRANSCRIPTION_FAILED", message(exception, "转写服务失败，请重新上传。"));
            clearAudio(userId, id, path);
            return get(userId, id);
        }
        clearAudio(userId, id, path);
        return analyzeTask(userId, id);
    }

    InterviewImport get(String userId, String id) { return api(task(userId, id)); }

    InterviewImport analyze(String userId, String id) {
        ImportRow row = task(userId, id);
        if (row.transcript.isBlank()) throw new IllegalArgumentException("尚无可分析的转写文本，请重新上传录音。");
        if (row.finalInterviewId != null) return api(row);
        return analyzeTask(userId, id);
    }

    @Transactional
    InterviewDetail confirm(String userId, String id, ImportConfirmRequest request) {
        ImportRow row = task(userId, id);
        if (row.finalInterviewId != null) return interviews.get(userId, row.finalInterviewId);
        if (row.transcript.isBlank()) throw new IllegalArgumentException("尚无可保存的转写文本。");
        if (request == null) throw new IllegalArgumentException("请至少保留一道问答后再保存。");
        List<QuestionRequest> questions = confirmedQuestions(request.questions());
        InterviewDetail detail = row.targetInterviewId == null ? createLegacy(userId, request, questions) : interviews.appendQuestions(userId, row.targetInterviewId, questions);
        jdbc.sql("UPDATE interview_audio_imports SET status='SAVED',final_interview_id=:final,error='',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user AND final_interview_id IS NULL")
            .param("final", detail.interview().id()).param("id", id).param("user", userId).update();
        return detail;
    }

    private InterviewDetail createLegacy(String userId, ImportConfirmRequest request, List<QuestionRequest> questions) {
        if (request == null || request.interview() == null) throw new IllegalArgumentException("请补全本次面试信息。");
        return interviews.createWithQuestions(userId, request.interview(), questions);
    }

    private InterviewImport analyzeTask(String userId, String id) {
        ImportRow row = task(userId, id);
        if (row.finalInterviewId != null) return api(row);
        jdbc.sql("UPDATE interview_audio_imports SET status='ANALYZING',error='',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user").param("id", id).param("user", userId).update();
        try {
            List<ImportedQuestion> all = new ArrayList<>();
            for (String chunk : chunks(row.transcript)) merge(all, parse(model.replyJson(prompt(chunk))));
            if (all.isEmpty()) throw new IllegalArgumentException("未识别出有效问答；请检查转写文本后重试分析或手工整理。");
            if (all.size() > 80) throw new IllegalArgumentException("识别出的问答超过 80 条，请缩短录音或手工整理后重试。");
            List<ImportedQuestion> ordered = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) { ImportedQuestion item = all.get(i); ordered.add(new ImportedQuestion(item.question(), item.answer(), i + 1, item.speakerEvidence())); }
            jdbc.sql("UPDATE interview_audio_imports SET status='READY',analysis_json=:analysis,error='',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user")
                .param("analysis", json.writeValueAsString(java.util.Map.of("questions", ordered))).param("id", id).param("user", userId).update();
        } catch (Exception exception) {
            fail(userId, id, "ANALYSIS_FAILED", message(exception, "问答识别失败，请重试分析或手工整理。"));
        }
        return get(userId, id);
    }

    private List<QuestionRequest> confirmedQuestions(List<ImportedQuestion> items) {
        if (items == null || items.isEmpty() || items.size() > 80) throw new IllegalArgumentException("请保留 1 到 80 条有效问答后再保存。");
        List<QuestionRequest> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ImportedQuestion item = items.get(i);
            if (item == null || item.orderIndex() != i + 1 || text(item.question(), 4_000).isBlank()) throw new IllegalArgumentException("问题不能为空且顺序必须连续，请检查后重试。");
            String answer = text(item.answer(), 20_000);
            result.add(new QuestionRequest(text(item.question(), 4_000), answer, answer.isBlank() ? "UNANSWERED" : "UNCERTAIN"));
        }
        return result;
    }

    private List<ImportedQuestion> parse(JsonNode root) {
        JsonNode items = root.path("questions");
        if (!items.isArray() || items.isEmpty() || items.size() > 80) throw new IllegalArgumentException("模型 JSON 非法、字段缺失或未识别到有效问答。");
        List<ImportedQuestion> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.path("orderIndex").canConvertToInt() || item.path("orderIndex").asInt() != i + 1) throw new IllegalArgumentException("模型 JSON 的问答顺序无效。");
            String question = text(item.path("question").isTextual() ? item.path("question").asText() : null, 4_000);
            String answer = text(item.path("answer").isTextual() ? item.path("answer").asText() : null, 20_000);
            String evidence = text(item.path("speakerEvidence").isTextual() ? item.path("speakerEvidence").asText() : null, 2_000);
            if (question.isBlank()) throw new IllegalArgumentException("模型 JSON 存在空问题。");
            result.add(new ImportedQuestion(question, answer, i + 1, evidence));
        }
        return result;
    }

    private void merge(List<ImportedQuestion> all, List<ImportedQuestion> next) {
        for (ImportedQuestion item : next) {
            if (!all.isEmpty() && normalized(all.getLast().question()).equals(normalized(item.question()))) {
                ImportedQuestion last = all.removeLast();
                String answer = last.answer().isBlank() ? item.answer() : item.answer().isBlank() || last.answer().contains(item.answer()) ? last.answer() : last.answer() + "\n" + item.answer();
                all.add(new ImportedQuestion(last.question(), answer, last.orderIndex(), last.speakerEvidence().isBlank() ? item.speakerEvidence() : last.speakerEvidence()));
            } else all.add(item);
        }
    }

    private List<String> chunks(String transcript) {
        List<String> result = new ArrayList<>(); int start = 0;
        while (start < transcript.length()) {
            int end = Math.min(start + CHUNK_CHARS, transcript.length());
            if (end < transcript.length()) {
                int boundary = Math.max(Math.max(transcript.lastIndexOf('\n', end), transcript.lastIndexOf('。', end)), Math.max(transcript.lastIndexOf('？', end), transcript.lastIndexOf('！', end)));
                if (boundary > start + CHUNK_CHARS / 2) end = boundary + 1;
            }
            result.add(transcript.substring(start, end)); start = end;
        }
        return result;
    }

    private String prompt(String transcript) {
        return "你是面试录音整理助手。仅根据以下转写文本识别真实的面试官提问和候选人回答，不得虚构问题、答案、经历、技术或数据。过滤寒暄、设备调试和闲聊；合并同一问题下连续回答；追问独立成题，除非显然只是上一句补充。说话人或归属无法确定时保留原文，并在 speakerEvidence 写‘待确认：’加对应片段，不能猜测。只返回严格 JSON：{questions:[{question:string,answer:string,orderIndex:number,speakerEvidence:string}]}。orderIndex 必须从 1 连续递增；answer 可为空，question 不可为空。\n转写文本：\n" + transcript;
    }

    private void clearAudio(String userId, String id, String path) {
        try { storage.deleteImport(path); jdbc.sql("UPDATE interview_audio_imports SET object_path=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user").param("id", id).param("user", userId).update(); }
        catch (RuntimeException ignored) { /* ponytail: best-effort cleanup; keep path for an operational cleanup job if storage is unavailable. */ }
    }
    private void fail(String userId, String id, String status, String error) { jdbc.sql("UPDATE interview_audio_imports SET status=:status,error=:error,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:user").param("status", status).param("error", error).param("id", id).param("user", userId).update(); }
    private ImportRow task(String userId, String id) { return jdbc.sql("SELECT id,status,original_filename,size_bytes,transcript,error,analysis_json,final_interview_id,target_interview_id FROM interview_audio_imports WHERE id=:id AND user_id=:user").param("id", id).param("user", userId).query((rs, row) -> new ImportRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9))).optional().orElseThrow(() -> new NoSuchElementException("导入任务不存在或无权访问。")); }
    private InterviewImport api(ImportRow row) { try { return new InterviewImport(row.id, row.status, row.filename, row.size, row.transcript, row.error, row.analysis == null ? List.of() : parse(json.readTree(row.analysis)), row.finalInterviewId); } catch (Exception exception) { throw new IllegalStateException("导入结果格式无效，请重新分析。"); } }
    static String validateAudio(byte[] bytes) { if (bytes.length == 0) throw new IllegalArgumentException("录音为空，请选择一段录音后重试。"); if (bytes.length > MAX_AUDIO_BYTES) throw new IllegalArgumentException("录音超过 25 MiB，请缩短或压缩后重试。"); String type = audioType(bytes); if (type == null) throw new IllegalArgumentException("仅支持 WebM、Ogg、MP3、MP4/M4A 或 WAV 音频，文件内容必须与格式一致。"); return type; }
    private static String audioType(byte[] b) { if (b.length > 12 && b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='A'&&b[10]=='V'&&b[11]=='E') return "audio/wav"; if (b.length > 4 && b[0]=='O'&&b[1]=='g'&&b[2]=='g'&&b[3]=='S') return "audio/ogg"; if (b.length > 4 && b[0]==0x1a&&b[1]==0x45&&b[2]==(byte)0xdf&&b[3]==(byte)0xa3) return "audio/webm"; if (b.length > 12 && b[4]=='f'&&b[5]=='t'&&b[6]=='y'&&b[7]=='p') return "audio/mp4"; if (b.length > 3 && b[0]=='I'&&b[1]=='D'&&b[2]=='3' || b.length > 2 && (b[0]&0xff)==0xff && (b[1]&0xe0)==0xe0) return "audio/mpeg"; return null; }
    private static String extension(String type) { return type.equals("audio/wav") ? ".wav" : type.equals("audio/ogg") ? ".ogg" : type.equals("audio/mpeg") ? ".mp3" : type.equals("audio/mp4") ? ".m4a" : ".webm"; }
    private static String safeName(String value) { return value == null || value.isBlank() ? "recording" : value.replaceAll("[^\\p{L}\\p{N}._-]", "_"); }
    private static String text(String value, int maximum) { String result = value == null ? "" : value.trim(); if (result.length() > maximum) throw new IllegalArgumentException("模型 JSON 字段过长。"); return result; }
    private static String limit(String value, String label, int maximum) { String result = value == null ? "" : value.trim(); if (result.isBlank()) throw new IllegalArgumentException(label + "为空，请重新上传。"); if (result.length() > maximum) throw new IllegalArgumentException(label + "超过 " + maximum + " 个字符，请缩短录音后重试。"); return result; }
    private static String normalized(String value) { return value.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT); }
    private static String message(Exception exception, String fallback) { String value = exception.getMessage(); return value == null || value.isBlank() ? fallback : value; }
    private record ImportRow(String id, String status, String filename, long size, String transcript, String error, String analysis, String finalInterviewId, String targetInterviewId) {}
}

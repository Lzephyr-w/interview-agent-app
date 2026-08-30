package com.interviewagent.weakness;

import static com.interviewagent.weakness.WeaknessApi.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.ReviewModelClient;
import com.interviewagent.interview.ReviewFailedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeaknessService {
    private static final Set<String> TAGS = Set.of("技术基础", "算法与数据结构", "系统设计", "项目深挖", "业务理解", "行为面", "沟通表达", "岗位匹配", "简历风险", "英语表达");
    private static final Set<String> STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS", "COMPLETED");
    private static final int MAX_ITEMS = 3;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ReviewModelClient model;

    public WeaknessService(JdbcClient jdbc, ObjectMapper json, ReviewModelClient model) {
        this.jdbc = jdbc;
        this.json = json;
        this.model = model;
    }

    public List<WeaknessItem> weaknesses(String userId) {
        WeaknessAnalysis analysis = analysis(userId);
        return analysis.stale() ? List.of() : analysis.items();
    }

    public WeaknessAnalysis analysis(String userId) {
        AnalysisInput input = input(userId);
        StoredAnalysis stored = stored(userId);
        if (stored == null) return new WeaknessAnalysis(null, null, false, List.of());
        if (!stored.fingerprint().equals(input.fingerprint())) return new WeaknessAnalysis(null, stored.updatedAt(), true, List.of());
        return new WeaknessAnalysis(stored.summary(), stored.updatedAt(), false, stored.items());
    }

    @Transactional
    public WeaknessAnalysis analyze(String userId) {
        AnalysisInput input = input(userId);
        ParsedAnalysis parsed = parse(model.replyJson(prompt(input)), input.questions());
        String items;
        try {
            items = json.writeValueAsString(parsed.items());
        } catch (Exception exception) {
            throw invalidOutput();
        }
        int updated = jdbc.sql("UPDATE weakness_analyses SET input_fingerprint = :fingerprint, summary = :summary, items_json = :items, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId")
            .param("userId", userId).param("fingerprint", input.fingerprint()).param("summary", parsed.summary()).param("items", items).update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO weakness_analyses (user_id, input_fingerprint, summary, items_json) VALUES (:userId, :fingerprint, :summary, :items)")
                .param("userId", userId).param("fingerprint", input.fingerprint()).param("summary", parsed.summary()).param("items", items).update();
        }
        StoredAnalysis stored = stored(userId);
        return new WeaknessAnalysis(stored.summary(), stored.updatedAt(), false, stored.items());
    }

    public WeaknessItem weakness(String userId, String tag) {
        if (!TAGS.contains(tag)) throw notFound();
        return weaknesses(userId).stream().filter(item -> item.tag().equals(tag)).findFirst().orElseThrow(WeaknessService::notFound);
    }

    public List<TrainingTask> tasks(String userId) {
        return jdbc.sql(taskQuery() + " WHERE t.user_id = :userId ORDER BY t.created_at DESC").param("userId", userId).query((rs, row) -> task(rs)).list();
    }

    public TrainingTask task(String userId, String id) {
        return jdbc.sql(taskQuery() + " WHERE t.id = :id AND t.user_id = :userId").param("id", id).param("userId", userId).query((rs, row) -> task(rs)).optional().orElseThrow(WeaknessService::notFound);
    }

    @Transactional
    public TrainingTask create(String userId, TrainingTaskRequest request) {
        String id = UUID.randomUUID().toString();
        ValidTask valid = validate(userId, request);
        jdbc.sql("INSERT INTO training_tasks (id, user_id, title, weakness_tag, action, status, completed_at, source_question_id, source_interview_id, source_review_report_id) VALUES (:id, :userId, :title, :tag, :action, :status, :completedAt, :sourceQuestionId, :sourceInterviewId, :sourceReviewReportId)")
            .param("id", id).param("userId", userId).param("title", valid.title()).param("tag", valid.tag()).param("action", valid.action()).param("status", valid.status()).param("completedAt", valid.completedAt()).param("sourceQuestionId", valid.sourceQuestionId()).param("sourceInterviewId", valid.sourceInterviewId()).param("sourceReviewReportId", valid.sourceReviewReportId()).update();
        return task(userId, id);
    }

    @Transactional
    TrainingTask update(String userId, String id, TrainingTaskRequest request) {
        task(userId, id);
        ValidTask valid = validate(userId, request);
        if (jdbc.sql("UPDATE training_tasks SET title = :title, weakness_tag = :tag, action = :action, status = :status, completed_at = :completedAt, source_question_id = :sourceQuestionId, source_interview_id = :sourceInterviewId, source_review_report_id = :sourceReviewReportId WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).param("title", valid.title()).param("tag", valid.tag()).param("action", valid.action()).param("status", valid.status()).param("completedAt", valid.completedAt()).param("sourceQuestionId", valid.sourceQuestionId()).param("sourceInterviewId", valid.sourceInterviewId()).param("sourceReviewReportId", valid.sourceReviewReportId()).update() == 0) throw notFound();
        return task(userId, id);
    }

    void delete(String userId, String id) {
        if (jdbc.sql("DELETE FROM training_tasks WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).update() == 0) throw notFound();
    }

    private AnalysisInput input(String userId) {
        List<InputInterview> interviews = jdbc.sql("SELECT i.id, i.company, i.role, i.interview_round, i.interview_type, i.interview_time, i.status, p.resume_file_id, rf.parsed_status, rf.parsed_text FROM interviews i LEFT JOIN interview_packages p ON p.id = i.interview_package_id LEFT JOIN resume_files rf ON rf.id = p.resume_file_id AND rf.user_id = i.user_id WHERE i.user_id = :userId ORDER BY i.id")
            .param("userId", userId).query((rs, row) -> inputInterview(rs)).list();
        List<InputQuestion> questions = interviews.stream().flatMap(interview -> interview.questions().stream()).toList();
        InputPayload payload = new InputPayload(interviews);
        try {
            return new AnalysisInput(json.writeValueAsString(payload), fingerprint(payload), questions);
        } catch (Exception exception) {
            throw new IllegalStateException("弱项分析输入读取失败。", exception);
        }
    }

    private InputInterview inputInterview(ResultSet rs) throws SQLException {
        String interviewId = rs.getString("id");
        LatestReview latest = jdbc.sql("SELECT id, readiness, summary, weakness_tags FROM review_reports WHERE interview_id = :interviewId ORDER BY created_at DESC, id DESC LIMIT 1")
            .param("interviewId", interviewId).query((review, row) -> new LatestReview(review.getString("id"), review.getString("readiness"), review.getString("summary"), review.getString("weakness_tags"))).optional().orElse(null);
        List<InputQuestion> questions = jdbc.sql("SELECT q.id, q.question_text, q.answer_text, q.self_assessment, qr.evaluation, qr.answer_evidence, qr.missing_evidence, qr.improvement_action, qr.recommended_answer_structure FROM interview_questions q LEFT JOIN question_reviews qr ON qr.interview_question_id = q.id AND qr.review_report_id = :reportId WHERE q.interview_id = :interviewId ORDER BY q.sort_order, q.created_at, q.id")
            .param("interviewId", interviewId).param("reportId", latest == null ? "" : latest.id()).query((question, row) -> new InputQuestion(question.getString("id"), question.getString("question_text"), question.getString("answer_text"), question.getString("self_assessment"), latest == null ? null : latest.id(), question.getString("evaluation"), question.getString("answer_evidence"), question.getString("missing_evidence"), question.getString("improvement_action"), question.getString("recommended_answer_structure"), interviewId, rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("interview_type"))).list();
        String resumeStatus = rs.getString("parsed_status");
        String resume = "READY".equals(resumeStatus) && usable(rs.getString("parsed_text")) ? rs.getString("parsed_text") : "待补充";
        return new InputInterview(interviewId, rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("interview_type"), rs.getObject("interview_time", OffsetDateTime.class), rs.getString("status"), rs.getString("resume_file_id"), resumeStatus == null ? "待补充" : resumeStatus, resume, latest == null ? null : latest.id(), latest == null ? null : latest.readiness(), latest == null ? null : latest.summary(), latest == null ? null : latest.tags(), questions);
    }

    private String prompt(AnalysisInput input) {
        return """
            你是面试复盘分析助手。仅根据下方 JSON 中已有资料生成中文 JSON，不得臆造简历事实、项目指标或面试内容。
            目标是总结当前账户最多 3 个具体薄弱点，而不是计数或复述标签。每个题目只能作为一个弱项的证据；每场面试只提供了最新复盘。资料不足必须写“待补充”。
            禁止输出通过概率、录用/淘汰建议、招聘结论、能力评级。
            """ + "标签只能是：" + String.join("、", TAGS) + "。\n" + """
            只输出 JSON：{"summary":"...","weaknesses":[{"tag":"十个既有标签之一","title":"具体标题","diagnosis":"诊断","action":"下一步动作","evidence":[{"questionId":"输入中的 ID","reason":"关联理由"}]}]}。
            weaknesses 最多 3 项，每项 evidence 为 1 到 3 个。不得返回用户、面试或复盘 ID，除了 evidence.questionId。
            输入：
            """ + input.json();
    }

    private ParsedAnalysis parse(JsonNode root, List<InputQuestion> questions) {
        if (!root.isObject()) throw invalidOutput();
        fields(root, Set.of("summary", "weaknesses"));
        String summary = text(root, "summary", 1_000);
        JsonNode values = root.path("weaknesses");
        if (!values.isArray() || values.size() > MAX_ITEMS) throw invalidOutput();
        Map<String, InputQuestion> questionById = new LinkedHashMap<>();
        questions.forEach(question -> questionById.put(question.id(), question));
        Set<String> tags = new LinkedHashSet<>();
        Set<String> usedQuestions = new LinkedHashSet<>();
        List<WeaknessItem> items = new ArrayList<>();
        for (JsonNode value : values) {
            fields(value, Set.of("tag", "title", "diagnosis", "action", "evidence"));
            String tag = text(value, "tag", 40);
            if (!TAGS.contains(tag) || !tags.add(tag)) throw invalidOutput();
            String title = text(value, "title", 120);
            String diagnosis = text(value, "diagnosis", 800);
            String action = text(value, "action", 800);
            JsonNode evidenceValues = value.path("evidence");
            if (!evidenceValues.isArray() || evidenceValues.isEmpty() || evidenceValues.size() > 3) throw invalidOutput();
            List<WeaknessEvidence> evidence = new ArrayList<>();
            for (JsonNode evidenceValue : evidenceValues) {
                fields(evidenceValue, Set.of("questionId", "reason"));
                String questionId = text(evidenceValue, "questionId", 120);
                String reason = text(evidenceValue, "reason", 500);
                InputQuestion question = questionById.get(questionId);
                if (question == null || !usedQuestions.add(questionId)) throw invalidOutput();
                evidence.add(new WeaknessEvidence(question.id(), question.reviewReportId(), question.interviewId(), display(question.questionText()), question.company(), question.role(), question.interviewRound(), question.interviewType(), reason));
            }
            items.add(new WeaknessItem(tag, title, diagnosis, action, evidence));
        }
        return new ParsedAnalysis(summary, items);
    }

    private StoredAnalysis stored(String userId) {
        return jdbc.sql("SELECT input_fingerprint, summary, items_json, updated_at FROM weakness_analyses WHERE user_id = :userId")
            .param("userId", userId).query((rs, row) -> stored(rs)).optional().orElse(null);
    }

    private StoredAnalysis stored(ResultSet rs) throws SQLException {
        try {
            return new StoredAnalysis(rs.getString("input_fingerprint"), rs.getString("summary"), json.readValue(rs.getString("items_json"), new TypeReference<List<WeaknessItem>>() {}), rs.getObject("updated_at", OffsetDateTime.class));
        } catch (Exception exception) {
            throw new IllegalStateException("弱项分析快照格式无效。", exception);
        }
    }

    private ValidTask validate(String userId, TrainingTaskRequest request) {
        String title = required(request.title(), "任务标题");
        String tag = required(request.weaknessTag(), "弱项标签");
        if (!TAGS.contains(tag)) throw new IllegalArgumentException("弱项标签值无效。");
        String action = required(request.action(), "练习内容");
        String status = required(request.status(), "状态");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("状态值无效。");
        String questionId = optional(request.sourceQuestionId());
        String interviewId = optional(request.sourceInterviewId());
        String reviewId = optional(request.sourceReviewReportId());
        QuestionOwner question = questionId == null ? null : jdbc.sql("SELECT q.id, q.interview_id FROM interview_questions q JOIN interviews i ON i.id = q.interview_id WHERE q.id = :id AND i.user_id = :userId")
            .param("id", questionId).param("userId", userId).query((rs, row) -> new QuestionOwner(rs.getString("id"), rs.getString("interview_id"))).optional().orElseThrow(WeaknessService::notFound);
        if (question != null) {
            if (interviewId != null && !interviewId.equals(question.interviewId())) throw notFound();
            interviewId = question.interviewId();
        }
        if (interviewId != null && !exists("SELECT COUNT(*) FROM interviews WHERE id = :id AND user_id = :userId", interviewId, userId)) throw notFound();
        if (reviewId != null) {
            String reportInterviewId = jdbc.sql("SELECT r.interview_id FROM review_reports r JOIN interviews i ON i.id = r.interview_id WHERE r.id = :id AND i.user_id = :userId")
                .param("id", reviewId).param("userId", userId).query(String.class).optional().orElseThrow(WeaknessService::notFound);
            if (interviewId != null && !interviewId.equals(reportInterviewId)) throw notFound();
            if (question != null && !questionInReview(reviewId, questionId)) throw notFound();
            interviewId = reportInterviewId;
        }
        return new ValidTask(title, tag, action, status, "COMPLETED".equals(status) ? OffsetDateTime.now() : null, questionId, interviewId, reviewId);
    }

    private boolean exists(String sql, String id, String userId) {
        return jdbc.sql(sql).param("id", id).param("userId", userId).query(Integer.class).single() > 0;
    }

    private boolean questionInReview(String reviewId, String questionId) {
        return jdbc.sql("SELECT COUNT(*) FROM question_reviews WHERE review_report_id = :reviewId AND interview_question_id = :questionId")
            .param("reviewId", reviewId).param("questionId", questionId).query(Integer.class).single() > 0;
    }

    private TrainingTask task(ResultSet rs) throws SQLException {
        String questionId = rs.getString("source_question_id");
        String interviewId = rs.getString("source_interview_id");
        String reviewId = rs.getString("source_review_report_id");
        String company = rs.getString("source_company");
        String label = company == null ? null : company + " · " + rs.getString("source_role");
        TrainingSource source = questionId == null && interviewId == null && reviewId == null ? null : new TrainingSource(questionId, rs.getString("source_question_text"), interviewId, reviewId, label, rs.getString("source_interview_type"));
        return new TrainingTask(rs.getString("id"), rs.getString("title"), rs.getString("weakness_tag"), rs.getString("action"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class), source);
    }

    private String taskQuery() {
        return "SELECT t.id, t.title, t.weakness_tag, t.action, t.status, t.created_at, t.completed_at, t.source_question_id, t.source_interview_id, t.source_review_report_id, q.question_text source_question_text, i.company source_company, i.role source_role, i.interview_type source_interview_type FROM training_tasks t LEFT JOIN interview_questions q ON q.id = t.source_question_id LEFT JOIN interviews i ON i.id = t.source_interview_id";
    }

    private String fingerprint(InputPayload payload) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("弱项分析输入读取失败。", exception);
        }
    }

    private static String text(JsonNode node, String field, int maxLength) {
        String value = node.path(field).isTextual() ? node.path(field).asText().trim() : "";
        if (value.isEmpty() || value.length() > maxLength || prohibited(value)) throw invalidOutput();
        return value;
    }

    private static boolean prohibited(String value) {
        String lower = value.toLowerCase();
        return lower.contains("通过率") || lower.contains("通过概率") || lower.contains("是否通过") || lower.contains("能否通过") || lower.contains("面试通过") || lower.contains("probability") || lower.contains("招聘结论") || lower.contains("录用") || lower.contains("淘汰") || lower.contains("能力评级") || lower.contains("hired") || lower.contains("not hired") || lower.contains("hire decision");
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalidOutput(); });
    }

    private static String required(String value, String label) {
        String result = optional(value);
        if (result == null) throw new IllegalArgumentException(label + "不能为空。");
        return result;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean usable(String value) {
        return value != null && !value.isBlank();
    }

    private static String display(String value) {
        return usable(value) ? value : "待补充";
    }

    private static ReviewFailedException invalidOutput() {
        return new ReviewFailedException("AI 弱项分析返回格式无效，请重试。");
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException("资源不存在或无权访问。");
    }

    private record AnalysisInput(String json, String fingerprint, List<InputQuestion> questions) {}
    private record InputPayload(List<InputInterview> interviews) {}
    private record InputInterview(String interviewId, String company, String role, String interviewRound, String interviewType, OffsetDateTime interviewTime, String status, String resumeFileId, String resumeStatus, String resumeText, String latestReviewReportId, String latestReviewReadiness, String latestReviewSummary, String latestReviewTags, List<InputQuestion> questions) {}
    private record InputQuestion(String id, String questionText, String answerText, String selfAssessment, String reviewReportId, String evaluation, String answerEvidence, String missingEvidence, String improvementAction, String recommendedAnswerStructure, String interviewId, String company, String role, String interviewRound, String interviewType) {}
    private record LatestReview(String id, String readiness, String summary, String tags) {}
    private record ParsedAnalysis(String summary, List<WeaknessItem> items) {}
    private record StoredAnalysis(String fingerprint, String summary, List<WeaknessItem> items, OffsetDateTime updatedAt) {}
    private record QuestionOwner(String id, String interviewId) {}
    private record ValidTask(String title, String tag, String action, String status, OffsetDateTime completedAt, String sourceQuestionId, String sourceInterviewId, String sourceReviewReportId) {}
}

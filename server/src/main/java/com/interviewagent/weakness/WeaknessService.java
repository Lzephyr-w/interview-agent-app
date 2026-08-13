package com.interviewagent.weakness;

import static com.interviewagent.weakness.WeaknessApi.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final Set<String> STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS", "COMPLETED");
    private static final Map<String, Rule> RULES = Map.ofEntries(
        Map.entry("技术基础", new Rule("补齐技术基础", "围绕概念、边界条件和复杂度各做一道解释练习。", "待补充", "概念 -> 原理 -> 边界 -> 示例")),
        Map.entry("算法与数据结构", new Rule("练习算法表达", "用一道代表题练习思路、复杂度与边界条件的完整表达。", "待补充", "思路 -> 正确性 -> 复杂度 -> 边界")),
        Map.entry("系统设计", new Rule("练习系统设计取舍", "练习需求、容量、架构、取舍与可靠性的结构化回答。", "待补充", "需求 -> 规模 -> 架构 -> 取舍 -> 可靠性")),
        Map.entry("项目深挖", new Rule("补全项目证据", "用背景、约束、行动、结果讲清个人贡献、指标与取舍。", "指标或个人贡献待补充", "背景 -> 约束 -> 行动 -> 结果 -> 复盘")),
        Map.entry("业务理解", new Rule("补足业务上下文", "先说目标与用户，再说明指标、方案和验证方式。", "待补充业务目标或指标", "目标 -> 用户 -> 指标 -> 方案 -> 验证")),
        Map.entry("行为面", new Rule("练习行为题结构", "准备具体场景，按 STAR 说明行动与结果，并补充复盘。", "待补充具体场景或结果", "情境 -> 任务 -> 行动 -> 结果 -> 复盘")),
        Map.entry("沟通表达", new Rule("练习结论先行", "先给结论，再用两到三个依据展开，最后确认问题是否回答到位。", "待补充可验证依据", "结论 -> 依据 -> 取舍 -> 回应追问")),
        Map.entry("岗位匹配", new Rule("映射岗位要求", "把岗位要求逐项映射到已有经历，并准备证据不足处的补充说明。", "待补充岗位要求与经历对应关系", "要求 -> 经历 -> 证据 -> 缺口")),
        Map.entry("简历风险", new Rule("核对简历事实", "逐条核对简历中的经历、时间、职责和结果，准备可验证的解释。", "待补充可验证事实", "事实 -> 个人职责 -> 结果 -> 追问")),
        Map.entry("英语表达", new Rule("练习英文回答", "用简短句子练习背景、行动、结果和追问的英文表达。", "待补充英文表达素材", "背景 -> 行动 -> 结果 -> 追问"))
    );

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public WeaknessService(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public List<WeaknessItem> weaknesses(String userId) {
        return aggregates(userId).values().stream().sorted(Comparator.comparingInt(Aggregate::count).reversed().thenComparing(Aggregate::latest, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Aggregate::tag))
            .limit(3).map(Aggregate::item).toList();
    }

    public WeaknessItem weakness(String userId, String tag) {
        if (!RULES.containsKey(tag)) throw notFound();
        Aggregate aggregate = aggregates(userId).get(tag);
        if (aggregate == null) throw notFound();
        return aggregate.item();
    }

    private Map<String, Aggregate> aggregates(String userId) {
        List<ReviewRow> reports = jdbc.sql("SELECT r.id report_id, r.interview_id, r.weakness_tags, r.created_at, i.company, i.role, i.interview_round, i.interview_type FROM review_reports r JOIN interviews i ON i.id = r.interview_id WHERE i.user_id = :userId ORDER BY r.created_at DESC")
            .param("userId", userId).query((rs, row) -> reviewRow(rs)).list();
        Map<String, List<AdviceRow>> adviceByReport = jdbc.sql("SELECT qr.review_report_id, qr.interview_question_id, iq.question_text, qr.improvement_action, qr.missing_evidence, qr.recommended_answer_structure FROM question_reviews qr JOIN review_reports r ON r.id = qr.review_report_id JOIN interviews i ON i.id = r.interview_id JOIN interview_questions iq ON iq.id = qr.interview_question_id WHERE i.user_id = :userId")
            .param("userId", userId).query((rs, row) -> new AdviceRow(rs.getString("review_report_id"), rs.getString("interview_question_id"), rs.getString("question_text"), rs.getString("improvement_action"), rs.getString("missing_evidence"), rs.getString("recommended_answer_structure"))).list()
            .stream().collect(java.util.stream.Collectors.groupingBy(AdviceRow::reportId));

        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (ReviewRow report : reports) {
            for (String tag : new LinkedHashSet<>(stringList(report.tags()))) {
                Rule rule = RULES.get(tag);
                if (rule == null) continue;
                aggregates.computeIfAbsent(tag, ignored -> new Aggregate(rule)).add(report, adviceByReport.getOrDefault(report.reportId(), List.of()));
            }
        }
        return aggregates;
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
        jdbc.sql("INSERT INTO training_tasks (id, user_id, title, weakness_tag, action, status, completed_at, source_interview_id, source_review_report_id) VALUES (:id, :userId, :title, :tag, :action, :status, :completedAt, :sourceInterviewId, :sourceReviewReportId)")
            .param("id", id).param("userId", userId).param("title", valid.title()).param("tag", valid.tag()).param("action", valid.action()).param("status", valid.status()).param("completedAt", valid.completedAt()).param("sourceInterviewId", valid.sourceInterviewId()).param("sourceReviewReportId", valid.sourceReviewReportId()).update();
        return task(userId, id);
    }

    @Transactional
    TrainingTask update(String userId, String id, TrainingTaskRequest request) {
        task(userId, id);
        ValidTask valid = validate(userId, request);
        if (jdbc.sql("UPDATE training_tasks SET title = :title, weakness_tag = :tag, action = :action, status = :status, completed_at = :completedAt, source_interview_id = :sourceInterviewId, source_review_report_id = :sourceReviewReportId WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).param("title", valid.title()).param("tag", valid.tag()).param("action", valid.action()).param("status", valid.status()).param("completedAt", valid.completedAt()).param("sourceInterviewId", valid.sourceInterviewId()).param("sourceReviewReportId", valid.sourceReviewReportId()).update() == 0) throw notFound();
        return task(userId, id);
    }

    void delete(String userId, String id) {
        if (jdbc.sql("DELETE FROM training_tasks WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).update() == 0) throw notFound();
    }

    private ValidTask validate(String userId, TrainingTaskRequest request) {
        String title = required(request.title(), "任务标题");
        String tag = required(request.weaknessTag(), "弱项标签");
        if (!RULES.containsKey(tag)) throw new IllegalArgumentException("弱项标签值无效。");
        String action = required(request.action(), "练习内容");
        String status = required(request.status(), "状态");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("状态值无效。");
        String sourceInterviewId = optional(request.sourceInterviewId());
        String sourceReviewReportId = optional(request.sourceReviewReportId());
        if (sourceInterviewId != null && !exists("SELECT COUNT(*) FROM interviews WHERE id = :id AND user_id = :userId", sourceInterviewId, userId)) throw notFound();
        if (sourceReviewReportId != null) {
            String reportInterviewId = jdbc.sql("SELECT r.interview_id FROM review_reports r JOIN interviews i ON i.id = r.interview_id WHERE r.id = :id AND i.user_id = :userId")
                .param("id", sourceReviewReportId).param("userId", userId).query(String.class).optional().orElseThrow(WeaknessService::notFound);
            if (sourceInterviewId != null && !sourceInterviewId.equals(reportInterviewId)) throw notFound();
            sourceInterviewId = reportInterviewId;
        }
        return new ValidTask(title, tag, action, status, "COMPLETED".equals(status) ? OffsetDateTime.now() : null, sourceInterviewId, sourceReviewReportId);
    }

    private boolean exists(String sql, String id, String userId) { return jdbc.sql(sql).param("id", id).param("userId", userId).query(Integer.class).single() > 0; }

    private TrainingTask task(ResultSet rs) throws SQLException {
        String interviewId = rs.getString("source_interview_id");
        String reportId = rs.getString("source_review_report_id");
        String company = rs.getString("source_company");
        String label = company == null ? null : company + " · " + rs.getString("source_role");
        TrainingSource source = interviewId == null && reportId == null ? null : new TrainingSource(interviewId, reportId, label, rs.getString("source_interview_type"));
        return new TrainingTask(rs.getString("id"), rs.getString("title"), rs.getString("weakness_tag"), rs.getString("action"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class), source);
    }

    private String taskQuery() {
        return "SELECT t.id, t.title, t.weakness_tag, t.action, t.status, t.created_at, t.completed_at, t.source_interview_id, t.source_review_report_id, i.company source_company, i.role source_role, i.interview_type source_interview_type FROM training_tasks t LEFT JOIN interviews i ON i.id = t.source_interview_id";
    }

    private static ReviewRow reviewRow(ResultSet rs) throws SQLException { return new ReviewRow(rs.getString("report_id"), rs.getString("interview_id"), rs.getString("weakness_tags"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("interview_type")); }
    private List<String> stringList(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception exception) { throw new IllegalStateException("弱项数据格式无效。"); } }
    private static String required(String value, String label) { String result = optional(value); if (result == null) throw new IllegalArgumentException(label + "不能为空。"); return result; }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static boolean usable(String value) { return value != null && !value.isBlank() && !value.startsWith("待补充"); }
    private static NoSuchElementException notFound() { return new NoSuchElementException("资源不存在或无权访问。"); }

    private record Rule(String title, String action, String missingEvidence, String structure) {}
    private record ReviewRow(String reportId, String interviewId, String tags, OffsetDateTime reviewedAt, String company, String role, String interviewRound, String interviewType) {}
    private record AdviceRow(String reportId, String questionId, String questionText, String action, String missingEvidence, String structure) {
        WeaknessEvidence evidence() { return new WeaknessEvidence(questionId, usable(questionText) ? questionText : "待补充", usable(action) ? action : "待补充", usable(missingEvidence) ? missingEvidence : "待补充", usable(structure) ? structure : "待补充"); }
    }
    private record ValidTask(String title, String tag, String action, String status, OffsetDateTime completedAt, String sourceInterviewId, String sourceReviewReportId) {}

    private static final class Aggregate {
        private final String tag;
        private final Rule rule;
        private final List<WeaknessSource> sources = new ArrayList<>();
        private final LinkedHashSet<String> actions = new LinkedHashSet<>();
        private final LinkedHashSet<String> missing = new LinkedHashSet<>();
        private final LinkedHashSet<String> structures = new LinkedHashSet<>();

        Aggregate(Rule rule) { this.tag = RULES.entrySet().stream().filter(entry -> entry.getValue() == rule).map(Map.Entry::getKey).findFirst().orElseThrow(); this.rule = rule; }
        void add(ReviewRow report, List<AdviceRow> advice) {
            sources.add(new WeaknessSource(report.interviewId(), report.reportId(), report.company(), report.role(), report.interviewRound(), report.interviewType(), report.reviewedAt(), advice.stream().filter(item -> item.reportId().equals(report.reportId())).map(AdviceRow::evidence).toList()));
            advice.stream().filter(item -> item.reportId().equals(report.reportId())).forEach(item -> { if (usable(item.action())) actions.add(item.action()); if (usable(item.missingEvidence())) missing.add(item.missingEvidence()); if (usable(item.structure())) structures.add(item.structure()); });
        }
        int count() { return sources.size(); }
        OffsetDateTime latest() { return sources.stream().map(WeaknessSource::reviewedAt).max(Comparator.naturalOrder()).orElse(null); }
        String tag() { return tag; }
        WeaknessItem item() { return new WeaknessItem(tag, count(), new TrainingSuggestion(rule.title(), actions.stream().findFirst().orElse(rule.action()), actions.isEmpty() ? "缺少可用逐题建议，使用固定训练映射。" : "优先沿用已有复盘中的逐题改进动作。", missing.stream().findFirst().orElse("待补充"), structures.stream().findFirst().orElse(rule.structure())), sources); }
    }
}

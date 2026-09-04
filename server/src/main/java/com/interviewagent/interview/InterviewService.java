package com.interviewagent.interview;

import com.interviewagent.ai.ReviewModelClient;
import static com.interviewagent.interview.InterviewApi.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
public class InterviewService {
    private static final String SUMMARY_COLUMNS = "SELECT i.id, i.company, i.role, i.interview_round, i.interview_time, i.status, i.result, i.interview_package_id, i.interview_type, CASE WHEN i.interview_type = 'REAL' THEN 'REAL' WHEN EXISTS (SELECT 1 FROM ai_mock_interviews ai WHERE ai.final_interview_id = i.id) THEN 'AI_VOICE' ELSE 'AI_TEXT' END AS simulation_type FROM interviews i";
    private static final Set<String> STATUSES = Set.of("PENDING_REVIEW", "REVIEWED");
    private static final Set<String> RESULTS = Set.of("UNKNOWN", "PASSED", "REJECTED", "PENDING");
    private static final Set<String> ASSESSMENTS = Set.of("GOOD", "UNCERTAIN", "UNANSWERED");
    private static final Set<String> READINESS = Set.of("准备不足", "基本准备", "准备充分");
    private static final Set<String> WEAKNESS_TAGS = Set.of("技术基础", "算法与数据结构", "系统设计", "项目深挖", "业务理解", "行为面", "沟通表达", "岗位匹配", "简历风险", "英语表达");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ReviewModelClient model;

    InterviewService(JdbcClient jdbc, ObjectMapper json, ReviewModelClient model) { this.jdbc = jdbc; this.json = json; this.model = model; }

    public List<InterviewSummary> list(String userId) {
        return jdbc.sql(SUMMARY_COLUMNS + " WHERE i.user_id = :userId ORDER BY i.interview_time DESC")
            .param("userId", userId).query((rs, row) -> summary(rs)).list();
    }

    public InterviewDetail get(String userId, String id) {
        InterviewSummary interview = ownedInterview(userId, id);
        String[] text = jdbc.sql("SELECT notes, transcript FROM interviews WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId)
            .query((rs, row) -> new String[] { rs.getString("notes"), rs.getString("transcript") }).single();
        return new InterviewDetail(interview, text[0], text[1], questions(id), reports(id));
    }

    @Transactional
    InterviewDetail create(String userId, InterviewRequest request) {
        return create(userId, request, "REAL");
    }

    @Transactional
    private InterviewDetail create(String userId, InterviewRequest request, String interviewType) {
        String id = UUID.randomUUID().toString();
        InterviewSummary interview = validate(id, userId, request);
        jdbc.sql("INSERT INTO interviews (id, user_id, interview_package_id, company, role, interview_round, interview_time, status, result, notes, interview_type) VALUES (:id, :userId, :packageId, :company, :role, :round, :time, :status, :result, :notes, :type)")
            .param("id", id).param("userId", userId).param("packageId", interview.interviewPackageId()).param("company", interview.company()).param("role", interview.role()).param("round", interview.interviewRound()).param("time", interview.interviewTime()).param("status", interview.status()).param("result", interview.result()).param("notes", optional(request.notes(), 8_000, "备注")).param("type", interviewType).update();
        return get(userId, id);
    }

    @Transactional
    public InterviewDetail createFromMock(String userId, InterviewRequest request, List<QuestionRequest> questionRequests) {
        InterviewDetail created = create(userId, request, "MOCK");
        int order = 0;
        for (QuestionRequest questionRequest : questionRequests) {
            insertQuestion(created.interview().id(), order++, questionRequest);
        }
        return get(userId, created.interview().id());
    }

    @Transactional
    public InterviewDetail createWithQuestions(String userId, InterviewRequest request, List<QuestionRequest> questionRequests) {
        InterviewDetail created = create(userId, request, "REAL");
        int order = 0;
        for (QuestionRequest questionRequest : questionRequests) insertQuestion(created.interview().id(), order++, questionRequest);
        return get(userId, created.interview().id());
    }

    @Transactional
    public InterviewDetail appendQuestions(String userId, String interviewId, List<QuestionRequest> questionRequests) {
        requireEditableQuestions(userId, interviewId);
        int order = jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id = :id").param("id", interviewId).query(Integer.class).single();
        for (QuestionRequest questionRequest : questionRequests) insertQuestion(interviewId, order++, questionRequest);
        return get(userId, interviewId);
    }

    public void ensureEditableQuestions(String userId, String interviewId) { requireEditableQuestions(userId, interviewId); }

    @Transactional
    InterviewDetail update(String userId, String id, InterviewRequest request) {
        ownedInterview(userId, id);
        InterviewSummary interview = validate(id, userId, request);
        jdbc.sql("UPDATE interviews SET interview_package_id = :packageId, company = :company, role = :role, interview_round = :round, interview_time = :time, status = :status, result = :result, notes = :notes, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).param("packageId", interview.interviewPackageId()).param("company", interview.company()).param("role", interview.role()).param("round", interview.interviewRound()).param("time", interview.interviewTime()).param("status", interview.status()).param("result", interview.result()).param("notes", optional(request.notes(), 8_000, "备注")).update();
        return get(userId, id);
    }

    @Transactional
    void delete(String userId, String id) {
        jdbc.sql("UPDATE mock_interviews SET finished_interview_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE finished_interview_id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).update();
        jdbc.sql("UPDATE ai_mock_interviews SET final_interview_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE final_interview_id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).update();
        if (jdbc.sql("DELETE FROM interviews WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).update() == 0) throw notFound();
    }

    @Transactional
    InterviewQuestion createQuestion(String userId, String interviewId, QuestionRequest request) {
        requireEditableQuestions(userId, interviewId);
        int order = jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id = :id").param("id", interviewId).query(Integer.class).single();
        return insertQuestion(interviewId, order, request);
    }

    InterviewQuestion updateQuestion(String userId, String interviewId, String questionId, QuestionRequest request) {
        requireEditableQuestions(userId, interviewId);
        InterviewQuestion question = question(questionId, interviewId);
        QuestionRequest valid = questionRequest(request);
        if (jdbc.sql("UPDATE interview_questions SET question_text = :question, answer_text = :answer, self_assessment = :assessment, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND interview_id = :interviewId")
            .param("id", questionId).param("interviewId", interviewId).param("question", valid.questionText()).param("answer", valid.answerText()).param("assessment", valid.selfAssessment()).update() == 0) throw notFound();
        return new InterviewQuestion(question.id(), valid.questionText(), valid.answerText(), valid.selfAssessment(), question.sortOrder());
    }

    void deleteQuestion(String userId, String interviewId, String questionId) {
        requireEditableQuestions(userId, interviewId);
        if (jdbc.sql("DELETE FROM interview_questions WHERE id = :id AND interview_id = :interviewId").param("id", questionId).param("interviewId", interviewId).update() == 0) throw notFound();
    }

    @Transactional
    List<InterviewQuestion> segmentTranscript(String userId, String interviewId, TranscriptRequest request) {
        requireEditableQuestions(userId, interviewId);
        String transcript = required(request.transcript(), "转写文本", 40_000);
        List<QuestionRequest> sections = new ArrayList<>();
        for (String section : transcript.split("(?:\\r?\\n){2,}")) {
            String[] lines = section.trim().split("\\r?\\n", 2);
            if (!lines[0].isBlank()) sections.add(new QuestionRequest(stripLabel(lines[0]), lines.length == 2 ? stripLabel(lines[1]) : "", lines.length == 2 ? "UNCERTAIN" : "UNANSWERED"));
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("请用空行分隔每道题及其回答。");
        jdbc.sql("UPDATE interviews SET transcript = :transcript, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId").param("transcript", transcript).param("id", interviewId).param("userId", userId).update();
        int start = jdbc.sql("SELECT COUNT(*) FROM interview_questions WHERE interview_id = :id").param("id", interviewId).query(Integer.class).single();
        List<InterviewQuestion> created = new ArrayList<>();
        for (QuestionRequest section : sections) created.add(insertQuestion(interviewId, start++, section));
        return created;
    }

    @Transactional
    ReviewReport review(String userId, String interviewId) {
        InterviewSummary interview = ownedInterview(userId, interviewId);
        List<InterviewQuestion> questions = questions(interviewId);
        if (questions.isEmpty()) throw new IllegalArgumentException("请至少添加一道问题和回答后再发起复盘。");
        JsonNode output = model.review(prompt(userId, interview, questions));
        ParsedReview parsed = parse(output, questions);
        String reportId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO review_reports (id, interview_id, readiness, summary, weakness_tags) VALUES (:id, :interviewId, :readiness, :summary, :tags)")
            .param("id", reportId).param("interviewId", interviewId).param("readiness", parsed.readiness).param("summary", parsed.summary).param("tags", jsonValue(parsed.tags)).update();
        for (ParsedQuestionReview item : parsed.questions) {
            jdbc.sql("INSERT INTO question_reviews (id, review_report_id, interview_question_id, evaluation, answer_evidence, missing_evidence, improvement_action, recommended_answer_structure, possible_followups) VALUES (:id, :reportId, :questionId, :evaluation, :evidence, :missing, :action, :structure, :followups)")
                .param("id", UUID.randomUUID().toString()).param("reportId", reportId).param("questionId", item.questionId).param("evaluation", item.evaluation).param("evidence", item.answerEvidence).param("missing", item.missingEvidence).param("action", item.improvementAction).param("structure", item.recommendedAnswerStructure).param("followups", jsonValue(item.possibleFollowups)).update();
        }
        jdbc.sql("UPDATE interviews SET status = 'REVIEWED', updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId").param("id", interviewId).param("userId", userId).update();
        return reports(interviewId).getFirst();
    }

    @Transactional
    void deleteReview(String userId, String interviewId, String reviewId) {
        ownedInterview(userId, interviewId);
        if (jdbc.sql("DELETE FROM review_reports WHERE id = :reviewId AND interview_id = :interviewId")
            .param("reviewId", reviewId).param("interviewId", interviewId).update() == 0) throw notFound();
        if (jdbc.sql("SELECT COUNT(*) FROM review_reports WHERE interview_id = :interviewId").param("interviewId", interviewId).query(Integer.class).single() == 0) {
            jdbc.sql("UPDATE interviews SET status = 'PENDING_REVIEW', updated_at = CURRENT_TIMESTAMP WHERE id = :interviewId AND user_id = :userId").param("interviewId", interviewId).param("userId", userId).update();
        }
    }

    private InterviewSummary validate(String id, String userId, InterviewRequest request) {
        String packageId = required(request.interviewPackageId(), "面试包");
        if (jdbc.sql("SELECT COUNT(*) FROM interview_packages WHERE id = :id AND user_id = :userId").param("id", packageId).param("userId", userId).query(Integer.class).single() == 0) throw notFound();
        if (request.interviewTime() == null) throw new IllegalArgumentException("面试时间不能为空。");
        String status = enumValue(request.status(), "状态", STATUSES); String result = enumValue(request.result(), "结果", RESULTS);
        return new InterviewSummary(id, required(request.company(), "公司", 200), required(request.role(), "岗位", 200), required(request.interviewRound(), "面试轮次", 200), request.interviewTime(), status, result, packageId, "REAL", "REAL");
    }

    private InterviewQuestion insertQuestion(String interviewId, int order, QuestionRequest request) {
        QuestionRequest valid = questionRequest(request); String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO interview_questions (id, interview_id, question_text, answer_text, self_assessment, sort_order) VALUES (:id, :interviewId, :question, :answer, :assessment, :order)")
            .param("id", id).param("interviewId", interviewId).param("question", valid.questionText()).param("answer", valid.answerText()).param("assessment", valid.selfAssessment()).param("order", order).update();
        return new InterviewQuestion(id, valid.questionText(), valid.answerText(), valid.selfAssessment(), order);
    }

    private QuestionRequest questionRequest(QuestionRequest request) {
        String assessment = enumValue(request.selfAssessment(), "回答标记", ASSESSMENTS); String answer = optional(request.answerText());
        if (answer.isBlank() && !assessment.equals("UNANSWERED")) throw new IllegalArgumentException("回答不能为空，未作答请标记为“没答上”。");
        return new QuestionRequest(required(request.questionText(), "问题", 4_000), limited(answer, "回答", 20_000), assessment);
    }

    private InterviewSummary ownedInterview(String userId, String id) {
        return jdbc.sql(SUMMARY_COLUMNS + " WHERE i.id = :id AND i.user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> summary(rs)).optional().orElseThrow(InterviewService::notFound);
    }

    private void requireEditableQuestions(String userId, String id) {
        InterviewSummary interview = ownedInterview(userId, id);
        if (!"REAL".equals(interview.simulationType())) throw new IllegalArgumentException("AI 模拟面试的问答仅可查看。");
    }

    private InterviewQuestion question(String questionId, String interviewId) {
        return jdbc.sql("SELECT id, question_text, answer_text, self_assessment, sort_order FROM interview_questions WHERE id = :id AND interview_id = :interviewId")
            .param("id", questionId).param("interviewId", interviewId).query((rs, row) -> question(rs)).optional().orElseThrow(InterviewService::notFound);
    }

    private List<InterviewQuestion> questions(String interviewId) {
        return jdbc.sql("SELECT id, question_text, answer_text, self_assessment, sort_order FROM interview_questions WHERE interview_id = :interviewId ORDER BY sort_order, created_at")
            .param("interviewId", interviewId).query((rs, row) -> question(rs)).list();
    }

    private List<ReviewReport> reports(String interviewId) {
        return jdbc.sql("SELECT id, readiness, summary, weakness_tags, created_at FROM review_reports WHERE interview_id = :interviewId ORDER BY created_at DESC")
            .param("interviewId", interviewId).query((rs, row) -> new ReviewReport(rs.getString("id"), rs.getString("readiness"), rs.getString("summary"), stringList(rs.getString("weakness_tags")), rs.getObject("created_at", OffsetDateTime.class), questionReviews(rs.getString("id")))).list();
    }

    private List<QuestionReview> questionReviews(String reportId) {
        return jdbc.sql("SELECT interview_question_id, evaluation, answer_evidence, missing_evidence, improvement_action, recommended_answer_structure, possible_followups FROM question_reviews WHERE review_report_id = :id")
            .param("id", reportId).query((rs, row) -> new QuestionReview(rs.getString("interview_question_id"), rs.getString("evaluation"), rs.getString("answer_evidence"), rs.getString("missing_evidence"), rs.getString("improvement_action"), rs.getString("recommended_answer_structure"), stringList(rs.getString("possible_followups")))).list();
    }

    private String prompt(String userId, InterviewSummary interview, List<InterviewQuestion> questions) {
        String jd = jdbc.sql("SELECT jd.content FROM interview_packages p LEFT JOIN job_descriptions jd ON jd.id = p.job_description_id AND jd.user_id = :userId WHERE p.id = :packageId AND p.user_id = :userId").param("userId", userId).param("packageId", interview.interviewPackageId()).query(String.class).optional().orElse("待补充");
        String resume = jdbc.sql("SELECT rf.parsed_text FROM interview_packages p LEFT JOIN resume_files rf ON rf.id = p.resume_file_id AND rf.user_id = :userId AND rf.parsed_status = 'READY' WHERE p.id = :packageId AND p.user_id = :userId")
            .param("userId", userId).param("packageId", interview.interviewPackageId()).query(String.class).optional().orElse("待补充（关联简历文件尚未解析）");
        List<Map<String, String>> cards = jdbc.sql("SELECT c.project_name, c.project_description_and_responsibilities, c.project_highlights, c.technology_stack FROM interview_package_evidence_cards link JOIN project_evidence_cards c ON c.id = link.evidence_card_id WHERE link.interview_package_id = :packageId AND c.user_id = :userId")
            .param("packageId", interview.interviewPackageId()).param("userId", userId).query((rs, row) -> Map.of("项目名称", rs.getString("project_name"), "项目描述与职责", rs.getString("project_description_and_responsibilities"), "项目亮点", rs.getString("project_highlights"), "技术栈", rs.getString("technology_stack"))).list();
        return "你是候选人面试复盘助手。只能依据当前用户授权且当前面试包关联的 JD、简历、证据卡和本场问答评价，绝不编造项目指标、经历、隐私信息或面试事实；资料不足时写“待补充”。不得输出能力评级、通过概率或招聘结论；readiness 仅表示资料准备完整度。固定弱项标签仅可为：" + String.join("、", WEAKNESS_TAGS) + "，最多3个。只返回 JSON：{readiness(准备不足|基本准备|准备充分),summary,weaknessTags:string[],questionReviews:[{questionId,evaluation,answerEvidence,missingEvidence,improvementAction,recommendedAnswerStructure,possibleFollowups:string[]}]}。questionReviews 必须恰好覆盖每个 questionId。\n面试=" + jsonValue(interview) + "\n简历=" + clip(resume, 12_000) + "\nJD=" + clip(jd, 12_000) + "\n证据卡=" + clip(jsonValue(cards.isEmpty() ? List.of(Map.of("资料", "待补充")) : cards), 12_000) + "\n问答=" + clip(jsonValue(questions), 30_000);
    }

    private ParsedReview parse(JsonNode root, List<InterviewQuestion> questions) {
        String joined = root.toString(); if (joined.contains("通过概率") || joined.toLowerCase().contains("pass probability")) throw new ReviewFailedException("AI 输出包含不允许的通过概率，请重新发起复盘。");
        String readiness = text(root, "readiness"); if (!READINESS.contains(readiness)) throw invalidFormat();
        List<String> tags = textArray(root.path("weaknessTags"), 3, 40); if (tags.size() > 3 || !WEAKNESS_TAGS.containsAll(tags)) throw invalidFormat();
        JsonNode items = root.path("questionReviews"); if (!items.isArray() || items.size() != questions.size()) throw invalidFormat();
        Set<String> ids = questions.stream().map(InterviewQuestion::id).collect(java.util.stream.Collectors.toSet());
        List<ParsedQuestionReview> parsed = new ArrayList<>();
        for (JsonNode item : items) {
            String id = text(item, "questionId"); if (!ids.remove(id)) throw invalidFormat();
            parsed.add(new ParsedQuestionReview(id, text(item, "evaluation", 4_000), text(item, "answerEvidence", 4_000), text(item, "missingEvidence", 4_000), text(item, "improvementAction", 4_000), text(item, "recommendedAnswerStructure", 4_000), textArray(item.path("possibleFollowups"), 5, 800)));
        }
        if (!ids.isEmpty()) throw invalidFormat();
        return new ParsedReview(readiness, text(root, "summary", 4_000), tags, parsed);
    }

    private String jsonValue(Object value) { try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("无法准备复盘资料。"); } }
    private List<String> stringList(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception exception) { throw new IllegalStateException("复盘数据格式无效。"); } }
    private static String text(JsonNode node, String name) { return text(node, name, 200); }
    private static String text(JsonNode node, String name, int maximum) { String value = node.path(name).asText("").trim(); if (value.isBlank() || value.length() > maximum) throw invalidFormat(); return value; }
    private static List<String> textArray(JsonNode node, int maximum, int maxChars) { if (!node.isArray() || node.size() > maximum) throw invalidFormat(); List<String> values = new ArrayList<>(); for (JsonNode item : node) { String value = item.asText("").trim(); if (value.isBlank() || value.length() > maxChars) throw invalidFormat(); values.add(value); } if (new LinkedHashSet<>(values).size() != values.size()) throw invalidFormat(); return values; }
    private static InterviewSummary summary(ResultSet rs) throws java.sql.SQLException { return new InterviewSummary(rs.getString("id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getObject("interview_time", OffsetDateTime.class), rs.getString("status"), rs.getString("result"), rs.getString("interview_package_id"), rs.getString("interview_type"), rs.getString("simulation_type")); }
    private static InterviewQuestion question(ResultSet rs) throws java.sql.SQLException { return new InterviewQuestion(rs.getString("id"), rs.getString("question_text"), rs.getString("answer_text"), rs.getString("self_assessment"), rs.getInt("sort_order")); }
    private static String required(String value, String label) { return required(value, label, Integer.MAX_VALUE); }
    private static String required(String value, String label, int maximum) { String result = optional(value); if (result.isBlank()) throw new IllegalArgumentException(label + "不能为空。"); return limited(result, label, maximum); }
    private static String optional(String value) { return value == null ? "" : value.trim(); }
    private static String optional(String value, int maximum, String label) { return limited(optional(value), label, maximum); }
    private static String limited(String value, String label, int maximum) { if (value.length() > maximum) throw new IllegalArgumentException(label + "过长，请控制在 " + maximum + " 个字符以内。"); return value; }
    private static String clip(String value, int maximum) { return value.length() > maximum ? value.substring(0, maximum) + "（已截断）" : value; }
    private static String enumValue(String value, String label, Set<String> allowed) { String result = required(value, label); if (!allowed.contains(result)) throw new IllegalArgumentException(label + "值无效。"); return result; }
    private static String stripLabel(String value) { return value.replaceFirst("^(问题|问|Q|回答|答|A)[：: ]*", "").trim(); }
    private static NoSuchElementException notFound() { return new NoSuchElementException("资源不存在或无权访问。"); }
    private static ReviewFailedException invalidFormat() { return new ReviewFailedException("AI 返回格式无效，请重新发起复盘。"); }
    private record ParsedReview(String readiness, String summary, List<String> tags, List<ParsedQuestionReview> questions) {}
    private record ParsedQuestionReview(String questionId, String evaluation, String answerEvidence, String missingEvidence, String improvementAction, String recommendedAnswerStructure, List<String> possibleFollowups) {}
}

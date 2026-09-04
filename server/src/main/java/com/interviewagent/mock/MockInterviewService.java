package com.interviewagent.mock;

import static com.interviewagent.interview.InterviewApi.InterviewRequest;
import static com.interviewagent.interview.InterviewApi.QuestionRequest;
import static com.interviewagent.mock.MockInterviewApi.*;

import com.interviewagent.interview.InterviewService;
import com.interviewagent.ai.ReviewModelClient;
import com.interviewagent.ai.AiMockTaskService;
import com.interviewagent.ai.AiMockTaskService.ClaimedTask;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MockInterviewService {
    private static final int MAIN_QUESTION_LIMIT = 4;
    private static final int MAX_MODEL_RETRIES = 3;
    private static final int MAX_ANSWER_CHARS = 8_000;
    private static final Set<String> ASSESSMENTS = Set.of("GOOD", "UNCERTAIN", "UNANSWERED");
    private final JdbcClient jdbc;
    private final InterviewService interviews;
    private final ReviewModelClient model;
    private final AiMockTaskService tasks;

    MockInterviewService(JdbcClient jdbc, InterviewService interviews, ReviewModelClient model, AiMockTaskService tasks) {
        this.jdbc = jdbc;
        this.interviews = interviews;
        this.model = model;
        this.tasks = tasks;
    }

    @Transactional
    public MockInterview create(String userId, StartRequest request) {
        PackageInfo selected = packageInfo(userId, required(request.interviewPackageId(), "面试包"));
        String id = UUID.randomUUID().toString();
        String company = limited(fallback(request.company(), selected.company()), "公司", 200);
        String role = limited(fallback(request.role(), selected.role()), "岗位", 200);
        String round = limited(fallback(request.interviewRound(), selected.interviewRound()), "面试轮次", 200);
        jdbc.sql("INSERT INTO mock_interviews (id, user_id, interview_package_id, company, role, interview_round, status, total_questions) VALUES (:id, :userId, :packageId, :company, :role, :round, 'RUNNING', :total)")
            .param("id", id).param("userId", userId).param("packageId", selected.id()).param("company", company).param("role", role).param("round", round).param("total", MAIN_QUESTION_LIMIT).update();
        tasks.enqueue(userId, "MOCK_CREATE", id, null);
        return detail(userId, id);
    }

    public MockInterview get(String userId, String id) {
        owned(userId, id);
        return detail(userId, id);
    }

    public MockInterview active(String userId) {
        return jdbc.sql("SELECT id FROM mock_interviews WHERE user_id=:user AND status='RUNNING' ORDER BY updated_at DESC LIMIT 1")
            .param("user", userId).query(String.class).optional().map(id -> detail(userId, id)).orElseThrow(MockInterviewService::notFound);
    }

    @Transactional
    public MockInterview answer(String userId, String id, AnswerRequest request) {
        MockSession session = owned(userId, id);
        if (session.finished()) return detail(userId, id);
        MockQuestion question = question(id, required(request.questionId(), "问题"));
        MockQuestion current = currentQuestion(id);
        if (!question.state().equals("OPEN")) return detail(userId, id);
        if (current == null || !current.id().equals(question.id())) throw new IllegalArgumentException("请按当前问题顺序提交。");
        String assessment = enumValue(request.selfAssessment(), "回答标记", ASSESSMENTS);
        String answer = limited(optional(request.answerText()), "回答", MAX_ANSWER_CHARS);
        if (answer.isBlank() && !assessment.equals("UNANSWERED")) throw new IllegalArgumentException("回答不能为空，未作答请使用跳过。");
        int updated = jdbc.sql("UPDATE mock_interview_questions SET answer_text = :answer, self_assessment = :assessment, state = 'ANSWERED', updated_at = CURRENT_TIMESTAMP WHERE id = :id AND mock_interview_id = :sessionId AND state = 'OPEN'")
            .param("answer", answer).param("assessment", assessment).param("id", question.id()).param("sessionId", id).update();
        if (updated == 0) return detail(userId, id);
        tasks.enqueue(userId, "MOCK_ANSWER", id, question.id());
        moveCursor(id);
        return detail(userId, id);
    }

    @Transactional
    public MockInterview skip(String userId, String id, String questionId) {
        MockSession session = owned(userId, id);
        if (session.finished()) return detail(userId, id);
        MockQuestion current = currentQuestion(id);
        if (questionId != null && !questionId.equals(current == null ? null : current.id())) throw new IllegalArgumentException("请按当前问题顺序跳过。");
        if (current == null) return detail(userId, id);
        int updated = jdbc.sql("UPDATE mock_interview_questions SET answer_text = '', self_assessment = 'UNANSWERED', state = 'SKIPPED', updated_at = CURRENT_TIMESTAMP WHERE id = :id AND state = 'OPEN'")
            .param("id", current.id()).update();
        if (updated == 0) return detail(userId, id);
        tasks.enqueue(userId, "MOCK_NEXT", id, current.id());
        moveCursor(id);
        return detail(userId, id);
    }

    @Transactional
    public MockInterview finish(String userId, String id) {
        MockSession session = owned(userId, id);
        if (session.finished()) return detail(userId, id);
        if (tasks.latest(userId, id) != null) throw new IllegalStateException("AI 正在处理中，请等待完成后再保存。");
        jdbc.sql("UPDATE mock_interview_questions SET answer_text = '', self_assessment = 'UNANSWERED', state = 'SKIPPED', updated_at = CURRENT_TIMESTAMP WHERE mock_interview_id = :id AND state = 'OPEN'")
            .param("id", id).update();
        List<QuestionRequest> questions = new ArrayList<>();
        for (MockQuestion question : questions(id)) {
            questions.add(new QuestionRequest(question.questionText(), question.answerText(), question.state().equals("ANSWERED") ? question.selfAssessment() : "UNANSWERED"));
        }
        String notes = "AI 文本模拟面试记录，问题、回答与 AI 逐题反馈保留在文本模拟会话中。";
        var formal = interviews.createFromMock(userId, new InterviewRequest(session.company(), session.role(), session.interviewRound(), OffsetDateTime.now(), session.packageId(), "PENDING_REVIEW", "UNKNOWN", notes), questions);
        jdbc.sql("UPDATE mock_interviews SET status = 'FINISHED', finished_interview_id = :formalId, current_question_index = :total, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("formalId", formal.interview().id()).param("total", MAIN_QUESTION_LIMIT).param("id", id).param("userId", userId).update();
        return detail(userId, id);
    }

    public void delete(String userId, String id) {
        tasks.deleteForResource(userId, id);
        if (jdbc.sql("DELETE FROM mock_interviews WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).update() == 0) throw notFound();
    }

    private MockInterview detail(String userId, String id) {
        MockSession session = owned(userId, id);
        List<MockQuestion> all = questions(id);
        MockQuestion current = all.stream().filter(item -> item.state().equals("OPEN")).findFirst().orElse(null);
        int completed = (int) all.stream().filter(item -> item.state().equals("ANSWERED") || item.state().equals("SKIPPED")).count();
        var task = tasks.latest(userId, id);
        int currentIndex = current == null ? (task == null ? MAIN_QUESTION_LIMIT : Math.min(MAIN_QUESTION_LIMIT, completed + 1)) : mainIndex(all, current);
        return new MockInterview(session.id(), session.company(), session.role(), session.interviewRound(), session.status(), "AI", true, "AI 将基于当前面试包的已解析简历、JD 和证据卡出题。", MAIN_QUESTION_LIMIT, completed, currentIndex, session.formalInterviewId(), session.createdAt(), session.updatedAt(), current, all, task);
    }

    public void processTask(ClaimedTask task) {
        switch (task.taskType()) {
            case "MOCK_CREATE" -> addMainQuestion(task.userId(), task.resourceId());
            case "MOCK_ANSWER" -> processAnswer(task.userId(), task.resourceId(), task.relatedId());
            case "MOCK_NEXT" -> processNext(task.userId(), task.resourceId(), task.relatedId());
            default -> throw new IllegalStateException("后台任务类型无效，请稍后重试。");
        }
    }

    private void processAnswer(String userId, String sessionId, String questionId) {
        MockQuestion answered = question(sessionId, questionId);
        if (answered.state().equals("ANSWERED") && answered.aiFeedback().isBlank()) {
            jdbc.sql("UPDATE mock_interview_questions SET ai_feedback=:feedback,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state='ANSWERED' AND ai_feedback=''")
                .param("feedback", feedback(answered.questionText(), answered.answerText())).param("id", questionId).update();
        }
        if (answered.state().equals("ANSWERED")) {
            if (answered.questionKind().equals("MAIN")) addFollowup(userId, sessionId, answered, answered.answerText());
            else addMainQuestion(userId, sessionId);
            moveCursor(sessionId);
        }
    }

    private void processNext(String userId, String sessionId, String questionId) {
        if (question(sessionId, questionId).state().equals("SKIPPED")) addMainQuestion(userId, sessionId);
        moveCursor(sessionId);
    }

    private void addMainQuestion(String userId, String sessionId) {
        MockSession session = owned(userId, sessionId);
        if (session.finished() || mainCount(sessionId) >= MAIN_QUESTION_LIMIT) return;
        List<MockQuestion> history = questions(sessionId);
        insertQuestion(UUID.randomUUID().toString(), sessionId, uniqueQuestion(questionPrompt(userId, session, history), history), "MAIN", null, "OPEN", nextOrder(history));
    }

    private void addFollowup(String userId, String sessionId, MockQuestion main, String answer) {
        if (answer.isBlank()) {
            addMainQuestion(userId, sessionId);
            return;
        }
        if (questions(sessionId).stream().anyMatch(item -> main.id().equals(item.parentQuestionId()))) return;
        MockSession session = owned(userId, sessionId);
        List<MockQuestion> history = questions(sessionId);
        insertQuestion(UUID.randomUUID().toString(), sessionId, uniqueQuestion(followupPrompt(userId, session, main, answer, history), history), "FOLLOW_UP", main.id(), "OPEN", nextOrder(history));
    }

    private String uniqueQuestion(String prompt, List<MockQuestion> history) {
        for (int attempt = 0; attempt < MAX_MODEL_RETRIES; attempt++) {
            String candidate = model.reply(prompt).trim();
            if (!candidate.isBlank() && !repeats(candidate, history)) return clip(candidate, 800);
        }
        throw new IllegalStateException("AI 连续生成重复问题，请稍后重试。");
    }

    private String feedback(String question, String answer) {
        return clip(model.reply("你是中文面试教练。只能依据当前用户授权的问题、回答、JD、简历和证据卡给出 2 句以内、可执行的简短反馈；不得编造经历、指标、隐私信息、能力评级、招聘结论或通过概率。资料不足请写待补充。\n问题：" + question + "\n回答：" + answer).trim(), 600);
    }

    private String questionPrompt(String userId, MockSession session, List<MockQuestion> history) {
        return "你是中文文本模拟面试官。只能根据以下当前用户授权且所选面试包关联的 JD、简历和证据卡生成 1 道新的主问题。不得编造经历、指标、隐私信息、能力评级、招聘结论或通过概率；缺失资料只能追问待补充的信息。只返回问题，不要解释。不要与已问问题重复或换词复问同一能力点。\n面试包：" + session.company() + " / " + session.role() + " / " + session.interviewRound() + "\n" + packageContext(userId, session.packageId()) + "\n已问问题：" + history.stream().map(MockQuestion::questionText).toList();
    }

    private String followupPrompt(String userId, MockSession session, MockQuestion main, String answer, List<MockQuestion> history) {
        return "你是中文文本模拟面试官。只能依据当前用户授权的 JD、简历、证据卡、本轮主问题和回答生成 1 道具体、可回答的追问，补足因果、贡献、证据或取舍中的一个缺口。不得编造经历、指标、隐私信息、能力评级、招聘结论或通过概率；只返回问题，不要解释；不能复述主问题。\n面试包：" + session.company() + " / " + session.role() + " / " + session.interviewRound() + "\n主问题：" + main.questionText() + "\n用户回答：" + answer + "\n" + packageContext(userId, session.packageId()) + "\n已问问题：" + history.stream().map(MockQuestion::questionText).toList();
    }

    private String packageContext(String userId, String packageId) {
        String jd = jdbc.sql("SELECT jd.content FROM interview_packages p LEFT JOIN job_descriptions jd ON jd.id = p.job_description_id AND jd.user_id = :userId WHERE p.id = :id AND p.user_id = :userId")
            .param("id", packageId).param("userId", userId).query(String.class).optional().orElse("待补充");
        String resume = jdbc.sql("SELECT rf.parsed_text FROM interview_packages p LEFT JOIN resume_files rf ON rf.id = p.resume_file_id AND rf.user_id = :userId AND rf.parsed_status = 'READY' WHERE p.id = :id AND p.user_id = :userId")
            .param("id", packageId).param("userId", userId).query(String.class).optional().orElse("待补充");
        List<String> cards = jdbc.sql("SELECT '项目名称：' || c.project_name || '；项目描述与职责：' || c.project_description_and_responsibilities || '；项目亮点：' || c.project_highlights || '；技术栈：' || c.technology_stack FROM interview_package_evidence_cards link JOIN project_evidence_cards c ON c.id = link.evidence_card_id AND c.user_id = :userId WHERE link.interview_package_id = :packageId")
            .param("userId", userId).param("packageId", packageId).query(String.class).list();
        return "简历：" + clip(resume, 6000) + "\nJD：" + clip(jd, 4000) + "\n证据卡：" + (cards.isEmpty() ? "待补充" : cards);
    }

    private void insertQuestion(String id, String sessionId, String text, String kind, String parentId, String state, int order) {
        jdbc.sql("INSERT INTO mock_interview_questions (id, mock_interview_id, question_text, question_kind, parent_question_id, state, sort_order) VALUES (:id, :sessionId, :text, :kind, :parentId, :state, :order)")
            .param("id", id).param("sessionId", sessionId).param("text", text).param("kind", kind).param("parentId", parentId).param("state", state).param("order", order).update();
    }

    private void moveCursor(String sessionId) {
        MockQuestion current = currentQuestion(sessionId);
        int index = current == null ? MAIN_QUESTION_LIMIT : mainIndex(questions(sessionId), current);
        jdbc.sql("UPDATE mock_interviews SET current_question_index = :index, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("index", index).param("id", sessionId).update();
    }

    private MockSession owned(String userId, String id) {
        return jdbc.sql("SELECT id, interview_package_id, company, role, interview_round, status, total_questions, finished_interview_id, created_at, updated_at FROM mock_interviews WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> session(rs)).optional().orElseThrow(MockInterviewService::notFound);
    }

    private PackageInfo packageInfo(String userId, String id) {
        return jdbc.sql("SELECT id, company, role, interview_round FROM interview_packages WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> new PackageInfo(rs.getString("id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"))).optional().orElseThrow(MockInterviewService::notFound);
    }

    private MockQuestion currentQuestion(String sessionId) {
        return jdbc.sql("SELECT id, question_text, answer_text, ai_feedback, self_assessment, question_kind, parent_question_id, state, sort_order FROM mock_interview_questions WHERE mock_interview_id = :id AND state = 'OPEN' ORDER BY sort_order LIMIT 1")
            .param("id", sessionId).query((rs, row) -> question(rs)).optional().orElse(null);
    }

    private MockQuestion question(String sessionId, String questionId) {
        return jdbc.sql("SELECT id, question_text, answer_text, ai_feedback, self_assessment, question_kind, parent_question_id, state, sort_order FROM mock_interview_questions WHERE mock_interview_id = :sessionId AND id = :id")
            .param("sessionId", sessionId).param("id", questionId).query((rs, row) -> question(rs)).optional().orElseThrow(MockInterviewService::notFound);
    }

    private List<MockQuestion> questions(String sessionId) {
        return jdbc.sql("SELECT id, question_text, answer_text, ai_feedback, self_assessment, question_kind, parent_question_id, state, sort_order FROM mock_interview_questions WHERE mock_interview_id = :id ORDER BY sort_order")
            .param("id", sessionId).query((rs, row) -> question(rs)).list();
    }

    private static MockSession session(ResultSet rs) throws java.sql.SQLException {
        return new MockSession(rs.getString("id"), rs.getString("interview_package_id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("status"), rs.getInt("total_questions"), rs.getString("finished_interview_id"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static MockQuestion question(ResultSet rs) throws java.sql.SQLException {
        return new MockQuestion(rs.getString("id"), rs.getString("question_text"), rs.getString("answer_text"), rs.getString("ai_feedback"), rs.getString("self_assessment"), rs.getString("question_kind"), rs.getString("parent_question_id"), rs.getString("state"), rs.getInt("sort_order"));
    }

    private int mainCount(String sessionId) {
        return jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id = :id AND question_kind = 'MAIN'")
            .param("id", sessionId).query(Integer.class).single();
    }

    private static int mainIndex(List<MockQuestion> questions, MockQuestion current) {
        String mainId = current.questionKind().equals("MAIN") ? current.id() : current.parentQuestionId();
        int index = 0;
        for (MockQuestion question : questions) {
            if (question.questionKind().equals("MAIN")) index++;
            if (question.id().equals(mainId)) return index;
        }
        return MAIN_QUESTION_LIMIT;
    }

    private static int nextOrder(List<MockQuestion> questions) { return questions.size(); }

    private static boolean repeats(String text, List<MockQuestion> history) {
        String candidate = normalize(text);
        return history.stream().map(item -> normalize(item.questionText())).anyMatch(previous -> previous.equals(candidate) || similarity(previous, candidate) >= 0.65);
    }

    private static String normalize(String text) { return text == null ? "" : text.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT); }
    private static double similarity(String left, String right) { if (left.length() < 2 || right.length() < 2) return 0; Set<String> a = grams(left), b = grams(right), both = new HashSet<>(a); both.retainAll(b); a.addAll(b); return a.isEmpty() ? 0 : (double) both.size() / a.size(); }
    private static Set<String> grams(String text) { Set<String> result = new HashSet<>(); for (int index = 1; index < text.length(); index++) result.add(text.substring(index - 1, index + 1)); return result; }
    private static String clip(String value, int limit) { String text = value == null || value.isBlank() ? "待补充" : value.trim(); return text.length() > limit ? text.substring(0, limit) + "（已截断）" : text; }
    private static String required(String value, String label) { String result = optional(value); if (result.isBlank()) throw new IllegalArgumentException(label + "不能为空。"); return result; }
    private static String optional(String value) { return value == null ? "" : value.trim(); }
    private static String fallback(String value, String fallback) { return optional(value).isBlank() ? fallback : value.trim(); }
    private static String limited(String value, String label, int maximum) { if (value.length() > maximum) throw new IllegalArgumentException(label + "过长，请控制在 " + maximum + " 个字符以内。"); return value; }
    private static String enumValue(String value, String label, Set<String> allowed) { String result = required(value, label); if (!allowed.contains(result)) throw new IllegalArgumentException(label + "值无效。"); return result; }
    private static NoSuchElementException notFound() { return new NoSuchElementException("资源不存在或无权访问。"); }

    private record PackageInfo(String id, String company, String role, String interviewRound) {}
    private record MockSession(String id, String packageId, String company, String role, String interviewRound, String status, int totalQuestions, String formalInterviewId, OffsetDateTime createdAt, OffsetDateTime updatedAt) { boolean finished() { return "FINISHED".equals(status); } }
}

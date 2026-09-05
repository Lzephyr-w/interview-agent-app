package com.interviewagent.mock;

import static com.interviewagent.interview.InterviewApi.InterviewRequest;
import static com.interviewagent.interview.InterviewApi.QuestionRequest;
import static com.interviewagent.mock.MockInterviewApi.*;

import com.interviewagent.interview.InterviewService;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.ai.SimulationMaterials;
import com.interviewagent.ai.SimulationContract;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.LinkedHashMap;
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
    private static final int MAX_ANSWER_CHARS = 8_000;
    private static final Set<String> ASSESSMENTS = Set.of("GOOD", "UNCERTAIN", "UNANSWERED");
    private final JdbcClient jdbc;
    private final InterviewService interviews;
    private final AgentPythonClient model;
    private final SimulationMaterials materials;
    private final AiMockTaskService tasks;

    MockInterviewService(JdbcClient jdbc, InterviewService interviews, AgentPythonClient model, AiMockTaskService tasks, SimulationMaterials materials) {
        this.jdbc = jdbc;
        this.interviews = interviews;
        this.model = model;
        this.materials = materials;
        this.tasks = tasks;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public MockInterview create(String userId, StartRequest request) {
        PackageInfo selected = packageInfo(userId, required(request.interviewPackageId(), "面试包"));
        String id = UUID.randomUUID().toString();
        String company = limited(fallback(request.company(), selected.company()), "公司", 200);
        String role = limited(fallback(request.role(), selected.role()), "岗位", 200);
        String round = limited(fallback(request.interviewRound(), selected.interviewRound()), "面试轮次", 200);
        jdbc.sql("INSERT INTO mock_interviews (id, user_id, interview_package_id, company, role, interview_round, status, total_questions, material_snapshot) VALUES (:id, :userId, :packageId, :company, :role, :round, 'RUNNING', :total, :snapshot)")
            .param("id", id).param("userId", userId).param("packageId", selected.id()).param("company", company).param("role", role).param("round", round).param("total", MAIN_QUESTION_LIMIT).param("snapshot",materials.capture(userId,selected.id()).toString()).update();
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
        lock(userId,id);
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
        lock(userId,id);
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
        lock(userId,id);
        MockSession session = owned(userId, id);
        if (session.finished()) return detail(userId, id);
        if (tasks.hasActive(userId, id)) throw new IllegalStateException("AI 正在处理中，请等待完成后再保存。");
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
        tasks.cancelForResource(userId,id);
        return detail(userId, id);
    }

    @Transactional
    public void delete(String userId, String id) {
        lock(userId,id);
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
        tasks.execute(task, () -> processTaskBody(task));
    }

    private void processTaskBody(ClaimedTask task) {
        switch (task.taskType()) {
            case "MOCK_CREATE" -> addMainQuestion(task.userId(), task.resourceId());
            case "MOCK_ANSWER" -> processAnswer(task.userId(), task.resourceId(), task.relatedId());
            case "MOCK_NEXT" -> processNext(task.userId(), task.resourceId(), task.relatedId());
            default -> throw new IllegalStateException("后台任务类型无效，请稍后重试。");
        }
    }

    private void processAnswer(String userId, String sessionId, String questionId) {
        MockQuestion answered=question(sessionId,questionId);
        if (answered.state().equals("ANSWERED") && answered.aiFeedback().isBlank()) {
            Map<String,Object> input=context(userId,owned(userId,sessionId),questions(sessionId));
            input.put("questionText",answered.questionText()); input.put("answer",answered.answerText());
            tasks.check();
            JsonNode result=model.simulate("TEXT_FEEDBACK",input);
            SimulationContract.result("TEXT_FEEDBACK",result);
            tasks.write(() -> jdbc.sql("UPDATE mock_interview_questions SET ai_feedback=:feedback,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state='ANSWERED' AND ai_feedback=''")
                .param("feedback",result.path("feedback").asText()).param("id",questionId).update());
        }
        if (answered.state().equals("ANSWERED")) {
            if (answered.questionKind().equals("MAIN")) addFollowup(userId,sessionId,answered,answered.answerText());
            else addMainQuestion(userId,sessionId);
            tasks.write(() -> moveCursor(sessionId));
        }
    }

    private void processNext(String userId, String sessionId, String questionId) {
        if (question(sessionId,questionId).state().equals("SKIPPED")) addMainQuestion(userId,sessionId);
        tasks.write(() -> moveCursor(sessionId));
    }

    private void addMainQuestion(String userId,String sessionId) {
        MockSession session=owned(userId,sessionId);
        if (session.finished() || mainCount(sessionId)>=MAIN_QUESTION_LIMIT || currentQuestion(sessionId)!=null) return;
        List<MockQuestion> history=questions(sessionId);
        String text=uniqueQuestion("TEXT_MAIN_QUESTION",context(userId,session,history),history);
        tasks.write(() -> {
            if (repeats(text,questions(sessionId))) throw SimulationContract.invalid();
            if (currentQuestion(sessionId)==null && mainCount(sessionId)<MAIN_QUESTION_LIMIT)
                insertQuestion(UUID.randomUUID().toString(),sessionId,text,"MAIN",null,"OPEN",nextOrder(history));
        });
    }

    private void addFollowup(String userId,String sessionId,MockQuestion main,String answer) {
        if (answer.isBlank()) { addMainQuestion(userId,sessionId); return; }
        List<MockQuestion> history=questions(sessionId);
        if (history.stream().anyMatch(item -> main.id().equals(item.parentQuestionId()))) return;
        Map<String,Object> input=context(userId,owned(userId,sessionId),history);
        input.put("questionText",main.questionText()); input.put("answer",answer);
        String text=uniqueQuestion("TEXT_FOLLOW_UP",input,history);
        tasks.write(() -> {
            if (repeats(text,questions(sessionId))) throw SimulationContract.invalid();
            if (questions(sessionId).stream().noneMatch(item -> main.id().equals(item.parentQuestionId())))
                insertQuestion(UUID.randomUUID().toString(),sessionId,text,"FOLLOW_UP",main.id(),"OPEN",nextOrder(history));
        });
    }

    private String uniqueQuestion(String operation,Map<String,Object> input,List<MockQuestion> history) {
        tasks.check();
        JsonNode result=model.simulate(operation,input);
        SimulationContract.result(operation,result);
        String text=result.path("questionText").asText().trim();
        if (repeats(text,history)) throw SimulationContract.invalid();
        return text;
    }

    private Map<String,Object> context(String user,MockSession session,List<MockQuestion> history) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("materials",materials.read(session.snapshot(),user,session.packageId()));
        result.put("history",history.stream().map(q -> Map.of("questionText",q.questionText(),"type",q.questionKind(),"competency","","projectName","","technology","")).toList());
        return result;
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

    private void lock(String user,String id) {
        jdbc.sql("SELECT id FROM mock_interviews WHERE id=:id AND user_id=:user FOR UPDATE").param("id",id).param("user",user).query(String.class).optional().orElseThrow(MockInterviewService::notFound);
    }

    private MockSession owned(String userId, String id) {
        return jdbc.sql("SELECT id, interview_package_id, company, role, interview_round, status, total_questions, finished_interview_id, created_at, updated_at, material_snapshot FROM mock_interviews WHERE id = :id AND user_id = :userId")
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
        return new MockSession(rs.getString("id"), rs.getString("interview_package_id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("status"), rs.getInt("total_questions"), rs.getString("finished_interview_id"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class), rs.getString("material_snapshot"));
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
    private record MockSession(String id, String packageId, String company, String role, String interviewRound, String status, int totalQuestions, String formalInterviewId, OffsetDateTime createdAt, OffsetDateTime updatedAt, String snapshot) { boolean finished() { return "FINISHED".equals(status); } }
}

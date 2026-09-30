package com.interviewagent.mock;

import static com.interviewagent.interview.InterviewApi.InterviewRequest;
import static com.interviewagent.interview.InterviewApi.QuestionRequest;
import static com.interviewagent.mock.MockInterviewApi.*;

import com.interviewagent.interview.InterviewService;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.ai.SimulationMaterials;
import com.interviewagent.ai.SimulationContract;
import com.interviewagent.knowledge.KnowledgeService;
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
    private static final int DEFAULT_MAIN_QUESTIONS = 4;
    private static final int MAX_MAIN_QUESTIONS = 10;
    private static final int MAX_FOLLOW_UPS = 2;
    private static final int MAX_ANSWER_CHARS = 8_000;
    private static final Set<String> ASSESSMENTS = Set.of("GOOD", "UNCERTAIN", "UNANSWERED");
    private final JdbcClient jdbc;
    private final InterviewService interviews;
    private final AgentPythonClient model;
    private final SimulationMaterials materials;
    private final AiMockTaskService tasks;
    private final KnowledgeService knowledge;

    MockInterviewService(JdbcClient jdbc, InterviewService interviews, AgentPythonClient model, AiMockTaskService tasks, SimulationMaterials materials, KnowledgeService knowledge) {
        this.jdbc = jdbc;
        this.interviews = interviews;
        this.model = model;
        this.materials = materials;
        this.tasks = tasks;
        this.knowledge = knowledge;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public MockInterview create(String userId, StartRequest request) {
        PackageInfo selected = packageInfo(userId, required(request.interviewPackageId(), "面试包"));
        String id = UUID.randomUUID().toString();
        String company = limited(fallback(request.company(), selected.company()), "公司", 200);
        String role = limited(fallback(request.role(), selected.role()), "岗位", 200);
        String round = limited(fallback(request.interviewRound(), selected.interviewRound()), "面试轮次", 200);
        String sourceMode = optional(request.sourceMode()).isBlank() ? "STANDARD" : request.sourceMode();
        if (!Set.of("STANDARD", "KNOWLEDGE").contains(sourceMode)) throw new IllegalArgumentException("模拟资料模式无效。");
        int totalQuestions = request.totalQuestions() == null ? DEFAULT_MAIN_QUESTIONS : request.totalQuestions();
        if (totalQuestions < 1 || totalQuestions > MAX_MAIN_QUESTIONS) throw new IllegalArgumentException("主问题数量应为 1–10 道。");
        List<String> documentIds = sourceMode.equals("KNOWLEDGE") ? knowledge.selectedDocuments(userId, request.categoryIds()) : List.of();
        jdbc.sql("INSERT INTO mock_interviews (id, user_id, interview_package_id, company, role, interview_round, status, total_questions, material_snapshot, source_mode, knowledge_document_ids) VALUES (:id, :userId, :packageId, :company, :role, :round, 'RUNNING', :total, :snapshot, :sourceMode, :documents)")
            .param("id", id).param("userId", userId).param("packageId", selected.id()).param("company", company).param("role", role).param("round", round).param("total", totalQuestions).param("snapshot",materials.capture(userId,selected.id()).toString())
            .param("sourceMode", sourceMode).param("documents", String.join(",", documentIds)).update();
        tasks.enqueue(userId, "MOCK_CREATE", id, null);
        return detail(userId, id);
    }

    public MockInterview get(String userId, String id) {
        owned(userId, id);
        return detail(userId, id);
    }

    public MockInterview active(String userId) {
        return jdbc.sql("SELECT id FROM mock_interviews WHERE user_id=:user AND status='RUNNING' ORDER BY updated_at DESC LIMIT 1")
            .param("user", userId).query(String.class).optional().map(id -> detail(userId, id)).orElse(null);
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
        if (needsNext(question, id, answer, assessment)) tasks.enqueue(userId, "MOCK_NEXT", id, question.id());
        if (!answer.isBlank()) tasks.enqueue(userId, "MOCK_FEEDBACK", id, question.id());
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
        jdbc.sql("UPDATE interview_questions iq SET ai_feedback = (SELECT mq.ai_feedback FROM mock_interview_questions mq WHERE mq.mock_interview_id = :mockId AND mq.sort_order = iq.sort_order) WHERE iq.interview_id = :formalId")
            .param("mockId", id).param("formalId", formal.interview().id()).update();
        jdbc.sql("UPDATE mock_interviews SET status = 'FINISHED', finished_interview_id = :formalId, current_question_index = :total, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("formalId", formal.interview().id()).param("total", session.totalQuestions()).param("id", id).param("userId", userId).update();
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
        int completed = (int) all.stream().filter(item -> item.questionKind().equals("MAIN") && (item.state().equals("ANSWERED") || item.state().equals("SKIPPED"))).count();
        var task = tasks.latest(userId, id);
        int currentIndex = current == null ? (task == null ? session.totalQuestions() : Math.min(session.totalQuestions(), completed + 1)) : mainIndex(all, current, session.totalQuestions());
        String message = session.sourceMode().equals("KNOWLEDGE") ? "AI 将依据所选知识库出题，面试包提供岗位和真实经历背景。" : "AI 将基于当前面试包的已解析简历、JD 和证据卡出题。";
        return new MockInterview(session.id(), session.company(), session.role(), session.interviewRound(), session.status(), "AI", session.sourceMode(), true, message, session.totalQuestions(), completed, currentIndex, session.formalInterviewId(), session.createdAt(), session.updatedAt(), current, all, task);
    }

    public void processTask(ClaimedTask task) {
        tasks.execute(task, () -> processTaskBody(task));
    }

    private void processTaskBody(ClaimedTask task) {
        switch (task.taskType()) {
            case "MOCK_CREATE" -> addMainQuestion(task.userId(), task.resourceId());
            case "MOCK_ANSWER" -> processAnswer(task.userId(), task.resourceId(), task.relatedId());
            case "MOCK_NEXT" -> processNext(task.userId(), task.resourceId(), task.relatedId());
            case "MOCK_FEEDBACK" -> processFeedback(task.userId(), task.resourceId(), task.relatedId());
            default -> throw new IllegalStateException("后台任务类型无效，请稍后重试。");
        }
    }

    private void processAnswer(String userId, String sessionId, String questionId) {
        MockQuestion answered=question(sessionId,questionId);
        if (answered.state().equals("ANSWERED")) tasks.write(() -> scheduleAnswerTasks(userId,sessionId,answered));
    }

    private void processNext(String userId, String sessionId, String questionId) {
        MockQuestion answered = question(sessionId,questionId);
        if (answered.state().equals("SKIPPED")) addMainQuestion(userId,sessionId);
        else if (answered.state().equals("ANSWERED")) {
            if (answered.questionKind().equals("MAIN")) addFollowup(userId,sessionId,answered,answered);
            else if (shouldAskSecondFollowup(answered,sessionId)) addFollowup(userId,sessionId,question(sessionId,answered.parentQuestionId()),answered);
            else addMainQuestion(userId,sessionId);
        }
        tasks.write(() -> moveCursor(sessionId));
    }

    private void processFeedback(String userId, String sessionId, String questionId) {
        MockQuestion answered=question(sessionId,questionId);
        if (!answered.state().equals("ANSWERED") || !answered.aiFeedback().isBlank() || answered.answerText().isBlank()) return;
        Map<String,Object> input=context(userId,owned(userId,sessionId),questions(sessionId));
        addKnowledge(input, sourceFor(answered.id()));
        input.put("questionText",answered.questionText()); input.put("answer",answered.answerText());
        tasks.check();
        JsonNode result=model.simulate("TEXT_FEEDBACK",input);
        SimulationContract.modelResult("TEXT_FEEDBACK",result);
        tasks.write(() -> jdbc.sql("UPDATE mock_interview_questions SET ai_feedback=:feedback,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND state='ANSWERED' AND ai_feedback=''")
            .param("feedback",result.path("feedback").asText()).param("id",questionId).update());
    }

    private void scheduleAnswerTasks(String userId,String sessionId,MockQuestion answered) {
        if (needsNext(answered,sessionId,answered.answerText(),answered.selfAssessment())) tasks.enqueue(userId,"MOCK_NEXT",sessionId,answered.id());
        if (!answered.answerText().isBlank() && answered.aiFeedback().isBlank()) tasks.enqueue(userId,"MOCK_FEEDBACK",sessionId,answered.id());
    }

    private boolean needsNext(MockQuestion question,String sessionId,String answer,String assessment) {
        if (question.questionKind().equals("MAIN")) return !answer.isBlank() || mainCount(sessionId)<totalQuestions(sessionId);
        return (!answer.isBlank() && assessment.equals("UNCERTAIN") && followupCount(sessionId,question.parentQuestionId())<MAX_FOLLOW_UPS)
            || mainCount(sessionId)<totalQuestions(sessionId);
    }

    private boolean shouldAskSecondFollowup(MockQuestion answered,String sessionId) {
        return !answered.answerText().isBlank() && answered.selfAssessment().equals("UNCERTAIN")
            && followupCount(sessionId,answered.parentQuestionId())<MAX_FOLLOW_UPS;
    }

    private void addMainQuestion(String userId,String sessionId) {
        MockSession session=owned(userId,sessionId);
        if (session.finished() || mainCount(sessionId)>=session.totalQuestions() || currentQuestion(sessionId)!=null) return;
        List<MockQuestion> history=questions(sessionId);
        KnowledgeService.Source source = null;
        Map<String,Object> input=context(userId,session,history);
        if (session.sourceMode().equals("KNOWLEDGE")) {
            String query=session.role()+" "+session.interviewRound()+" "+materials.read(session.snapshot(),userId,session.packageId()).path("jd").asText();
            Set<String> used=new HashSet<>(jdbc.sql("SELECT source_segment_id FROM mock_interview_questions WHERE mock_interview_id=:id AND source_segment_id IS NOT NULL AND question_kind='MAIN'")
                .param("id",sessionId).query(String.class).list());
            source=knowledge.retrieve(userId,List.of(session.documentIds().split(",")),query,used);
            addKnowledge(input,source);
        }
        String text=uniqueQuestion("TEXT_MAIN_QUESTION",input,history);
        KnowledgeService.Source selectedSource=source;
        tasks.write(() -> {
            if (repeats(text,questions(sessionId))) throw SimulationContract.retryableInvalid();
            if (currentQuestion(sessionId)==null && mainCount(sessionId)<session.totalQuestions())
                insertQuestion(UUID.randomUUID().toString(),sessionId,text,"MAIN",null,"OPEN",nextOrder(history),selectedSource);
        });
    }

    private void addFollowup(String userId,String sessionId,MockQuestion main,MockQuestion answered) {
        if (answered.answerText().isBlank()) { addMainQuestion(userId,sessionId); return; }
        List<MockQuestion> history=questions(sessionId);
        if (followupCount(sessionId,main.id())>=MAX_FOLLOW_UPS) return;
        Map<String,Object> input=context(userId,owned(userId,sessionId),history);
        KnowledgeService.Source source=sourceFor(main.id());
        addKnowledge(input,source);
        input.put("questionText",answered.questionText()); input.put("answer",answered.answerText());
        String text=uniqueQuestion("TEXT_FOLLOW_UP",input,history);
        tasks.write(() -> {
            if (repeats(text,questions(sessionId))) throw SimulationContract.retryableInvalid();
            if (followupCount(sessionId,main.id())<MAX_FOLLOW_UPS && currentQuestion(sessionId)==null)
                insertQuestion(UUID.randomUUID().toString(),sessionId,text,"FOLLOW_UP",main.id(),"OPEN",nextOrder(history),source);
        });
    }

    private String uniqueQuestion(String operation,Map<String,Object> input,List<MockQuestion> history) {
        tasks.check();
        JsonNode result=model.simulate(operation,input);
        SimulationContract.modelResult(operation,result);
        String text=result.path("questionText").asText().trim();
        if (repeats(text,history)) throw SimulationContract.retryableInvalid();
        return text;
    }

    private Map<String,Object> context(String user,MockSession session,List<MockQuestion> history) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("materials",materials.read(session.snapshot(),user,session.packageId()));
        // ponytail: the model contract accepts ten recent questions; database history still guards all repeats.
        result.put("history",history.subList(Math.max(0, history.size()-10),history.size()).stream()
            .map(q -> Map.of("questionText",q.questionText(),"type",q.questionKind(),"competency","","projectName","","technology","")).toList());
        return result;
    }

    private static void addKnowledge(Map<String,Object> input, KnowledgeService.Source source) {
        if (source == null) return;
        String text = "文档：" + source.title() + "；位置：" + source.location() + "\n内容：" + source.text();
        if (!source.answer().isBlank()) text += "\n参考答案（仅供反馈，不能当作当前用户经历）：" + source.answer();
        if (!source.reminder().isBlank()) text += "\n面试提醒：" + source.reminder();
        if (text.length()>6000) throw new IllegalArgumentException("知识库片段过长，请拆分文档。");
        input.put("knowledge", text);
    }

    private KnowledgeService.Source sourceFor(String questionId) {
        return jdbc.sql("SELECT source_segment_id,source_document_id,source_title,source_location,source_text,reference_answer,source_reminder FROM mock_interview_questions WHERE id=:id")
            .param("id",questionId).query((rs,row)->rs.getString(1)==null?null:new KnowledgeService.Source(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)))
            .optional().orElse(null);
    }

    private void insertQuestion(String id, String sessionId, String text, String kind, String parentId, String state, int order, KnowledgeService.Source source) {
        jdbc.sql("INSERT INTO mock_interview_questions (id,mock_interview_id,question_text,question_kind,parent_question_id,state,sort_order,source_document_id,source_segment_id,source_title,source_location,source_text,reference_answer,source_reminder) VALUES (:id,:sessionId,:text,:kind,:parentId,:state,:order,:document,:segment,:title,:location,:sourceText,:reference,:reminder)")
            .param("id", id).param("sessionId", sessionId).param("text", text).param("kind", kind).param("parentId", parentId).param("state", state).param("order", order)
            .param("document",source==null?null:source.documentId()).param("segment",source==null?null:source.id())
            .param("title",source==null?null:source.title()).param("location",source==null?null:source.location())
            .param("sourceText",source==null?null:source.text()).param("reference",source==null?null:source.answer())
            .param("reminder",source==null?null:source.reminder()).update();
    }

    private void moveCursor(String sessionId) {
        MockQuestion current = currentQuestion(sessionId);
        int total = totalQuestions(sessionId);
        int index = current == null ? total : mainIndex(questions(sessionId), current, total);
        jdbc.sql("UPDATE mock_interviews SET current_question_index = :index, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("index", index).param("id", sessionId).update();
    }

    private void lock(String user,String id) {
        jdbc.sql("SELECT id FROM mock_interviews WHERE id=:id AND user_id=:user FOR UPDATE").param("id",id).param("user",user).query(String.class).optional().orElseThrow(MockInterviewService::notFound);
    }

    private MockSession owned(String userId, String id) {
        return jdbc.sql("SELECT id, interview_package_id, company, role, interview_round, status, total_questions, finished_interview_id, created_at, updated_at, material_snapshot, source_mode, knowledge_document_ids FROM mock_interviews WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> session(rs)).optional().orElseThrow(MockInterviewService::notFound);
    }

    private PackageInfo packageInfo(String userId, String id) {
        return jdbc.sql("SELECT id, company, role, interview_round FROM interview_packages WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> new PackageInfo(rs.getString("id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"))).optional().orElseThrow(MockInterviewService::notFound);
    }

    private MockQuestion currentQuestion(String sessionId) {
        return jdbc.sql("SELECT id, question_text, answer_text, ai_feedback, self_assessment, question_kind, parent_question_id, state, sort_order, source_title, source_location FROM mock_interview_questions WHERE mock_interview_id = :id AND state = 'OPEN' ORDER BY sort_order LIMIT 1")
            .param("id", sessionId).query((rs, row) -> question(rs)).optional().orElse(null);
    }

    private MockQuestion question(String sessionId, String questionId) {
        return jdbc.sql("SELECT id, question_text, answer_text, ai_feedback, self_assessment, question_kind, parent_question_id, state, sort_order, source_title, source_location FROM mock_interview_questions WHERE mock_interview_id = :sessionId AND id = :id")
            .param("sessionId", sessionId).param("id", questionId).query((rs, row) -> question(rs)).optional().orElseThrow(MockInterviewService::notFound);
    }

    private List<MockQuestion> questions(String sessionId) {
        return jdbc.sql("SELECT id, question_text, answer_text, ai_feedback, self_assessment, question_kind, parent_question_id, state, sort_order, source_title, source_location FROM mock_interview_questions WHERE mock_interview_id = :id ORDER BY sort_order")
            .param("id", sessionId).query((rs, row) -> question(rs)).list();
    }

    private static MockSession session(ResultSet rs) throws java.sql.SQLException {
        return new MockSession(rs.getString("id"), rs.getString("interview_package_id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("status"), rs.getInt("total_questions"), rs.getString("finished_interview_id"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class), rs.getString("material_snapshot"), rs.getString("source_mode"), rs.getString("knowledge_document_ids"));
    }

    private static MockQuestion question(ResultSet rs) throws java.sql.SQLException {
        return new MockQuestion(rs.getString("id"), rs.getString("question_text"), rs.getString("answer_text"), rs.getString("ai_feedback"), rs.getString("self_assessment"), rs.getString("question_kind"), rs.getString("parent_question_id"), rs.getString("state"), rs.getInt("sort_order"), rs.getString("source_title"), rs.getString("source_location"));
    }

    private int mainCount(String sessionId) {
        return jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id = :id AND question_kind = 'MAIN'")
            .param("id", sessionId).query(Integer.class).single();
    }

    private int followupCount(String sessionId,String mainId) {
        return jdbc.sql("SELECT COUNT(*) FROM mock_interview_questions WHERE mock_interview_id=:session AND parent_question_id=:main AND question_kind='FOLLOW_UP'")
            .param("session",sessionId).param("main",mainId).query(Integer.class).single();
    }

    private int totalQuestions(String sessionId) {
        return jdbc.sql("SELECT total_questions FROM mock_interviews WHERE id=:id")
            .param("id",sessionId).query(Integer.class).single();
    }

    private static int mainIndex(List<MockQuestion> questions, MockQuestion current, int total) {
        String mainId = current.questionKind().equals("MAIN") ? current.id() : current.parentQuestionId();
        int index = 0;
        for (MockQuestion question : questions) {
            if (question.questionKind().equals("MAIN")) index++;
            if (question.id().equals(mainId)) return index;
        }
        return total;
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
    private record MockSession(String id, String packageId, String company, String role, String interviewRound, String status, int totalQuestions, String formalInterviewId, OffsetDateTime createdAt, OffsetDateTime updatedAt, String snapshot, String sourceMode, String documentIds) { boolean finished() { return "FINISHED".equals(status); } }
}

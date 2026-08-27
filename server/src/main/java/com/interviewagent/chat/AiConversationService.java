package com.interviewagent.chat;

import static com.interviewagent.chat.AiConversationApi.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.ai.AgentPythonClient;
import com.interviewagent.interview.InterviewService;
import com.interviewagent.interview.ReviewFailedException;
import com.interviewagent.material.MaterialService;
import com.interviewagent.material.ResumeFileService;
import com.interviewagent.weakness.WeaknessService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AiConversationService {
    private static final int MAX_MESSAGE_CHARS = 8_000;
    private static final int MAX_REPLY_CHARS = 20_000;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AgentPythonClient agent;
    private final ResumeFileService resumeFiles;
    private final MaterialService materials;
    private final InterviewService interviews;
    private final WeaknessService weaknesses;

    AiConversationService(JdbcClient jdbc, ObjectMapper json, AgentPythonClient agent, ResumeFileService resumeFiles, MaterialService materials, InterviewService interviews, WeaknessService weaknesses) {
        this.jdbc = jdbc;
        this.json = json;
        this.agent = agent;
        this.resumeFiles = resumeFiles;
        this.materials = materials;
        this.interviews = interviews;
        this.weaknesses = weaknesses;
    }

    List<ConversationSummary> list(String userId) {
        return jdbc
            .sql("SELECT id, title, created_at, updated_at FROM ai_conversations WHERE user_id = :userId ORDER BY updated_at DESC")
            .param("userId", userId)
            .query((rs, row) -> new ConversationSummary(rs.getString("id"), rs.getString("title"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)))
            .list();
    }

    @Transactional
    ConversationDetail create(String userId, ConversationRequest request) {
        String packageId = optional(request.interviewPackageId());
        PackageInfo packageInfo = packageId == null ? null : packageInfo(userId, packageId).orElseThrow(AiConversationService::notFound);
        String interviewId = optional(request.interviewId());
        String reviewReportId = optional(request.reviewReportId());
        String weaknessTag = optional(request.weaknessTag());
        String interviewPackageId = interviewId == null ? null : ownedInterview(userId, interviewId);
        if (interviewId != null && packageId != null && !packageId.equals(interviewPackageId)) throw new IllegalArgumentException("关联面试必须属于所选面试包。");
        if (reviewReportId != null) {
            if (interviewId == null) throw new IllegalArgumentException("关联复盘时必须同时关联其面试记录。");
            String reportInterviewId = ownedReview(userId, reviewReportId);
            if (!interviewId.equals(reportInterviewId)) throw notFound();
        }
        if (weaknessTag != null && !hasWeaknessTag(userId, weaknessTag)) throw notFound();
        String id = UUID.randomUUID().toString();
        String title = optional(request.title());
        if (title == null) title = packageInfo == null ? "AI Agent 对话" : "AI Agent · " + packageInfo.company() + " " + packageInfo.role();
        if (title.length() > 80) title = title.substring(0, 80);
        jdbc
            .sql("INSERT INTO ai_conversations (id, user_id, interview_package_id, interview_id, review_report_id, weakness_tag, title) VALUES (:id, :userId, :packageId, :interviewId, :reviewReportId, :weaknessTag, :title)")
            .param("id", id)
            .param("userId", userId)
            .param("packageId", packageId)
            .param("interviewId", interviewId)
            .param("reviewReportId", reviewReportId)
            .param("weaknessTag", weaknessTag)
            .param("title", title)
            .update();
        return get(userId, id);
    }

    ConversationDetail get(String userId, String id) {
        ConversationRow row = ownedConversation(userId, id);
        return new ConversationDetail(conversation(userId, row), messages(id));
    }

    @Transactional
    ConversationDetail addMessage(String userId, String conversationId, MessageRequest request) {
        ownedConversation(userId, conversationId);
        String content = required(request.content(), "问题");
        if (content.length() > MAX_MESSAGE_CHARS) throw new IllegalArgumentException("问题过长，请控制在 8000 个字符以内。");
        String clientRequestId = required(request.clientRequestId(), "客户端请求标识");
        if (clientRequestId.length() > 120) throw new IllegalArgumentException("客户端请求标识无效。");
        if (jdbc.sql("SELECT COUNT(*) FROM ai_conversation_messages WHERE conversation_id = :conversationId AND client_request_id = :requestId")
            .param("conversationId", conversationId).param("requestId", clientRequestId).query(Integer.class).single() == 0) {
            jdbc
                .sql("INSERT INTO ai_conversation_messages (id, conversation_id, role, content, status, client_request_id) VALUES (:id, :conversationId, 'USER', :content, 'SAVED', :requestId)")
                .param("id", UUID.randomUUID().toString())
                .param("conversationId", conversationId)
                .param("content", content)
                .param("requestId", clientRequestId)
                .update();
            jdbc.sql("UPDATE ai_conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
                .param("id", conversationId).param("userId", userId).update();
        }
        return get(userId, conversationId);
    }

    Message reply(String userId, String conversationId, String messageId) {
        ConversationRow row = ownedConversation(userId, conversationId);
        ownedUserMessage(conversationId, messageId);
        Message existing = assistantReply(conversationId, messageId);
        if (existing != null && ("COMPLETED".equals(existing.status()) || "PENDING".equals(existing.status()))) return existing;
        String assistantId;
        if (existing == null) {
            assistantId = UUID.randomUUID().toString();
            jdbc
                .sql("INSERT INTO ai_conversation_messages (id, conversation_id, role, content, status, reply_to_message_id) VALUES (:id, :conversationId, 'ASSISTANT', '', 'PENDING', :replyTo)")
                .param("id", assistantId).param("conversationId", conversationId).param("replyTo", messageId).update();
        } else {
            assistantId = existing.id();
            if (jdbc.sql("UPDATE ai_conversation_messages SET status = 'PENDING', error_message = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND status = 'FAILED'")
                .param("id", assistantId).update() == 0) return assistantMessage(conversationId, assistantId);
        }
        try {
            String answer = runAgent(userId, row);
            if (answer.length() > MAX_REPLY_CHARS) throw new ReviewFailedException("AI 回复过长，请缩小问题范围后重试。");
            if (containsProhibitedClaim(answer)) throw new ReviewFailedException("AI 回复包含不允许的结论，请修改问题后重试。");
            jdbc.sql("UPDATE ai_conversation_messages SET content = :content, status = 'COMPLETED', error_message = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", assistantId).param("content", answer).update();
        } catch (RuntimeException exception) {
            String message = exception instanceof ReviewFailedException && exception.getMessage() != null
                ? exception.getMessage() : "AI 回复失败，请重试。";
            jdbc.sql("UPDATE ai_conversation_messages SET status = 'FAILED', error_message = :error, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", assistantId).param("error", message).update();
        }
        jdbc.sql("UPDATE ai_conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("id", conversationId).param("userId", userId).update();
        return assistantMessage(conversationId, assistantId);
    }

    void delete(String userId, String id) {
        if (jdbc.sql("DELETE FROM ai_conversations WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).update() == 0) throw notFound();
    }

    private Conversation conversation(String userId, ResultSet rs) throws SQLException {
        return conversation(userId, row(rs));
    }

    private Conversation conversation(String userId, ConversationRow row) {
        return new Conversation(row.id(), row.title(), row.createdAt(), row.updatedAt(), context(userId, row).sources());
    }

    private ConversationRow ownedConversation(String userId, String id) {
        return jdbc
            .sql("SELECT id, interview_package_id, interview_id, review_report_id, weakness_tag, title, created_at, updated_at FROM ai_conversations WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> row(rs)).optional().orElseThrow(AiConversationService::notFound);
    }

    private List<Message> messages(String conversationId) {
        return jdbc
            .sql("SELECT id, role, content, status, error_message, client_request_id, reply_to_message_id, created_at, updated_at FROM ai_conversation_messages WHERE conversation_id = :conversationId ORDER BY created_at, id")
            .param("conversationId", conversationId).query((rs, row) -> message(rs)).list();
    }

    private Message ownedUserMessage(String conversationId, String id) {
        return jdbc
            .sql("SELECT id, role, content, status, error_message, client_request_id, reply_to_message_id, created_at, updated_at FROM ai_conversation_messages WHERE id = :id AND conversation_id = :conversationId AND role = 'USER'")
            .param("id", id).param("conversationId", conversationId).query((rs, row) -> message(rs)).optional().orElseThrow(AiConversationService::notFound);
    }

    private Message assistantReply(String conversationId, String userMessageId) {
        return jdbc
            .sql("SELECT id, role, content, status, error_message, client_request_id, reply_to_message_id, created_at, updated_at FROM ai_conversation_messages WHERE conversation_id = :conversationId AND reply_to_message_id = :messageId")
            .param("conversationId", conversationId).param("messageId", userMessageId).query((rs, row) -> message(rs)).optional().orElse(null);
    }

    private Message assistantMessage(String conversationId, String messageId) {
        return jdbc
            .sql("SELECT id, role, content, status, error_message, client_request_id, reply_to_message_id, created_at, updated_at FROM ai_conversation_messages WHERE id = :id AND conversation_id = :conversationId AND role = 'ASSISTANT'")
            .param("id", messageId).param("conversationId", conversationId).query((rs, row) -> message(rs)).single();
    }

    private Context context(String userId, ConversationRow row) {
        ContextBuilder context = new ContextBuilder();
        PackageInfo packageInfo = row.interviewPackageId() == null ? null : packageInfo(userId, row.interviewPackageId()).orElse(null);
        if (packageInfo == null) {
            context.sources.add(new ContextSource("启动资料", "未指定面试包或来源已删除", "Agent 可自主查询"));
        } else {
            context.add("面试包", packageInfo.company() + " · " + packageInfo.role() + " · " + packageInfo.interviewRound(), "面试包：" + packageInfo.company() + " / " + packageInfo.role() + " / " + packageInfo.interviewRound());
            addResumeContext(userId, packageInfo, context);
            if (packageInfo.jdId() == null) {
                context.unavailable("JD", "来源已删除");
            } else if (packageInfo.jdContent() == null || packageInfo.jdContent().isBlank()) {
                context.pending("JD", packageInfo.jdCompany() + " · " + packageInfo.jdRole());
            } else {
                context.add("JD", packageInfo.jdCompany() + " · " + packageInfo.jdRole(), "JD：\n" + packageInfo.jdContent());
            }
        }
        addInterviewContext(userId, row.interviewId(), context);
        addReviewContext(userId, row.reviewReportId(), context);
        addWeaknessContext(userId, row.weaknessTag(), context);
        if (packageInfo != null) addEvidenceCards(userId, packageInfo.id(), context);
        context.sources.add(new ContextSource("Agent 工具", "当前用户全部资料与训练任务", "可自主查询并创建训练任务"));
        return context.build();
    }

    private void addResumeContext(String userId, PackageInfo packageInfo, ContextBuilder context) {
        if (packageInfo.resumeFileId() == null || packageInfo.resumeFilename() == null) {
            context.unavailable("简历文件", "来源已删除");
            return;
        }
        ResumeFileService.ParsedResume resume = resumeFiles.parsedText(userId, packageInfo.resumeFileId());
        if (!"READY".equals(resume.status()) || resume.text() == null || resume.text().isBlank()) {
            context.pending("简历文件", packageInfo.resumeFilename() + "（" + (resume.error() == null ? "正文待解析" : resume.error()) + "）");
            return;
        }
        context.add("简历文件", packageInfo.resumeFilename(), "简历文件正文：\n" + resume.text(), resume.truncated() ? "已纳入（源文件解析文本已截断）" : "已纳入");
    }

    private void addEvidenceCards(String userId, String packageId, ContextBuilder context) {
        jdbc
            .sql("SELECT c.project_name, c.background_and_role, c.goal_and_metrics, c.constraints_and_tradeoffs, c.personal_contribution, c.result_and_retrospective FROM interview_package_evidence_cards link JOIN project_evidence_cards c ON c.id = link.evidence_card_id AND c.user_id = :userId WHERE link.interview_package_id = :packageId ORDER BY c.updated_at DESC")
            .param("userId", userId).param("packageId", packageId)
            .query((rs, index) -> evidenceText(rs)).list()
            .forEach(card -> context.add("项目证据卡", card.label(), card.text()));
    }

    private void addInterviewContext(String userId, String interviewId, ContextBuilder context) {
        if (interviewId == null) return;
        InterviewInfo interview = jdbc
            .sql("SELECT id, company, role, interview_round, interview_type FROM interviews WHERE id = :id AND user_id = :userId")
            .param("id", interviewId).param("userId", userId).query((rs, row) -> new InterviewInfo(rs.getString("id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("interview_type"))).optional().orElse(null);
        if (interview == null) { context.unavailable("面试记录", "来源已删除"); return; }
        List<String> questions = jdbc.sql("SELECT question_text, answer_text FROM interview_questions WHERE interview_id = :id ORDER BY sort_order, created_at")
            .param("id", interview.id()).query((rs, row) -> "问题：" + rs.getString("question_text") + "\n回答：" + rs.getString("answer_text")).list();
        context.add("面试记录", interview.company() + " · " + interview.role() + " · " + interview.interviewRound(), "面试记录（" + interview.interviewType() + "）：\n" + String.join("\n\n", questions.isEmpty() ? List.of("问答待补充") : questions));
    }

    private void addReviewContext(String userId, String reviewReportId, ContextBuilder context) {
        if (reviewReportId == null) return;
        ReviewInfo review = jdbc
            .sql("SELECT r.id, r.summary, r.weakness_tags, i.company, i.role FROM review_reports r JOIN interviews i ON i.id = r.interview_id WHERE r.id = :id AND i.user_id = :userId")
            .param("id", reviewReportId).param("userId", userId).query((rs, row) -> new ReviewInfo(rs.getString("id"), rs.getString("summary"), rs.getString("weakness_tags"), rs.getString("company"), rs.getString("role"))).optional().orElse(null);
        if (review == null) { context.unavailable("复盘报告", "来源已删除"); return; }
        List<String> details = jdbc.sql("SELECT evaluation, answer_evidence, missing_evidence, improvement_action FROM question_reviews WHERE review_report_id = :id")
            .param("id", review.id()).query((rs, row) -> "评价：" + rs.getString("evaluation") + "；依据：" + rs.getString("answer_evidence") + "；缺失：" + rs.getString("missing_evidence") + "；动作：" + rs.getString("improvement_action")).list();
        context.add("复盘报告", review.company() + " · " + review.role(), "复盘摘要：" + review.summary() + "\n弱项：" + String.join("、", stringList(review.tags())) + "\n逐题关键信息：\n" + String.join("\n", details));
    }

    private void addWeaknessContext(String userId, String weaknessTag, ContextBuilder context) {
        if (weaknessTag == null) return;
        int count = weaknessCount(userId, weaknessTag);
        if (count == 0) context.unavailable("薄弱点标签", weaknessTag + "（来源已删除）");
        else context.add("薄弱点标签", weaknessTag, "薄弱点标签：" + weaknessTag + "，当前复盘出现次数：" + count + "。仅根据已有复盘解释，缺失证据写待补充。");
    }

    private String runAgent(String userId, ConversationRow row) {
        return agent.reply(userId, row.id(), agentMessages(userId, row), "");
    }

    private List<Map<String, Object>> agentMessages(String userId, ConversationRow row) {
        Context context = context(userId, row);
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(Map.of("role", "system", "content", "你是可调用业务工具的面试准备 Agent。只能读取当前用户授权的 JD、简历、证据卡、面试、复盘、薄弱点和训练任务；启动资料只是线索，不是访问边界，可按需查询当前用户其他资料。不得编造候选人的经历、项目、指标、隐私信息或面试结果；资料不足必须明确写“待补充”。不得输出能力评级、通过概率或招聘结论。创建训练任务后必须在最终回复中列出任务标题和结果。\n\n【启动资料】\n" + context.text()));
        jdbc.sql("SELECT role, content FROM ai_conversation_messages WHERE conversation_id = :id AND (role = 'USER' OR status = 'COMPLETED') ORDER BY created_at, id")
            .param("id", row.id()).query((rs, index) -> Map.<String, Object>of("role", "USER".equals(rs.getString("role")) ? "user" : "assistant", "content", rs.getString("content"))).list().forEach(result::add);
        return result;
    }

    private java.util.Optional<PackageInfo> packageInfo(String userId, String id) {
        return jdbc
            .sql("SELECT p.id, p.company, p.role, p.interview_round, p.resume_file_id, p.job_description_id, jd.company jd_company, jd.role jd_role, jd.content jd_content, rf.original_filename FROM interview_packages p LEFT JOIN job_descriptions jd ON jd.id = p.job_description_id AND jd.user_id = :userId LEFT JOIN resume_files rf ON rf.id = p.resume_file_id AND rf.user_id = :userId WHERE p.id = :id AND p.user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> new PackageInfo(rs.getString("id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("resume_file_id"), rs.getString("job_description_id"), rs.getString("jd_company"), rs.getString("jd_role"), rs.getString("jd_content"), rs.getString("original_filename"))).optional();
    }

    private String ownedInterview(String userId, String id) {
        return jdbc.sql("SELECT interview_package_id FROM interviews WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).query(String.class).optional().orElseThrow(AiConversationService::notFound);
    }

    private String ownedReview(String userId, String id) {
        return jdbc.sql("SELECT r.interview_id FROM review_reports r JOIN interviews i ON i.id = r.interview_id WHERE r.id = :id AND i.user_id = :userId")
            .param("id", id).param("userId", userId).query(String.class).optional().orElseThrow(AiConversationService::notFound);
    }

    private boolean hasWeaknessTag(String userId, String tag) { return weaknessCount(userId, tag) > 0; }

    private int weaknessCount(String userId, String tag) {
        return (int) jdbc.sql("SELECT r.weakness_tags FROM review_reports r JOIN interviews i ON i.id = r.interview_id WHERE i.user_id = :userId")
            .param("userId", userId).query(String.class).list().stream().filter(tags -> stringList(tags).contains(tag)).count();
    }

    private EvidenceText evidenceText(ResultSet rs) throws SQLException {
        String label = rs.getString("project_name");
        return new EvidenceText(label, "项目证据卡：" + label + "\n背景与角色：" + rs.getString("background_and_role") + "\n目标与指标：" + rs.getString("goal_and_metrics") + "\n约束与取舍：" + rs.getString("constraints_and_tradeoffs") + "\n个人贡献：" + rs.getString("personal_contribution") + "\n结果与复盘：" + rs.getString("result_and_retrospective"));
    }

    private List<String> stringList(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("复盘数据格式无效。"); }
    }

    private static ConversationRow row(ResultSet rs) throws SQLException {
        return new ConversationRow(rs.getString("id"), rs.getString("interview_package_id"), rs.getString("interview_id"), rs.getString("review_report_id"), rs.getString("weakness_tag"), rs.getString("title"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static Message message(ResultSet rs) throws SQLException {
        return new Message(rs.getString("id"), rs.getString("role"), rs.getString("content"), rs.getString("status"), rs.getString("error_message"), rs.getString("client_request_id"), rs.getString("reply_to_message_id"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static boolean containsProhibitedClaim(String answer) {
        String lower = answer.toLowerCase();
        return answer.contains("通过概率") || lower.contains("pass probability") || answer.contains("已验证") || answer.contains("招聘结论");
    }

    private static String required(String value, String label) {
        String result = optional(value);
        if (result == null) throw new IllegalArgumentException(label + "不能为空。");
        return result;
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static NoSuchElementException notFound() { return new NoSuchElementException("资源不存在或无权访问。"); }

    private record ConversationRow(String id, String interviewPackageId, String interviewId, String reviewReportId, String weaknessTag, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    private record PackageInfo(String id, String company, String role, String interviewRound, String resumeFileId, String jdId, String jdCompany, String jdRole, String jdContent, String resumeFilename) {}
    private record InterviewInfo(String id, String company, String role, String interviewRound, String interviewType) {}
    private record ReviewInfo(String id, String summary, String tags, String company, String role) {}
    private record EvidenceText(String label, String text) {}
    private record Context(List<ContextSource> sources, String text) {}

    private static final class ContextBuilder {
        private final List<ContextSource> sources = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        void add(String type, String label, String value) {
            add(type, label, value, "已纳入");
        }

        void add(String type, String label, String value, String state) {
            sources.add(new ContextSource(type, label, state));
            text.append(value).append("\n\n");
        }

        void unavailable(String type, String label) { sources.add(new ContextSource(type, label, "来源已删除")); }
        void pending(String type, String label) { sources.add(new ContextSource(type, label, "待补充")); text.append(type).append("：待补充\n\n"); }
        Context build() { return new Context(List.copyOf(sources), text.isEmpty() ? "待补充" : text.toString()); }
    }
}

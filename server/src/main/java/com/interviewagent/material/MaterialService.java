package com.interviewagent.material;

import static com.interviewagent.material.MaterialRequests.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialService {
    private final JdbcClient jdbc;
    private final ResumeFileService resumeFiles;

    public MaterialService(JdbcClient jdbc, ResumeFileService resumeFiles) { this.jdbc = jdbc; this.resumeFiles = resumeFiles; }

    public List<Resume> resumes(String userId) {
        return jdbc.sql("SELECT id, title, content FROM resumes WHERE user_id = :userId ORDER BY updated_at DESC")
            .param("userId", userId).query(Resume.class).list();
    }

    public Resume resume(String userId, String id) {
        return jdbc.sql("SELECT id, title, content FROM resumes WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query(Resume.class).optional().orElseThrow(MaterialService::notFound);
    }

    public Resume createResume(String userId, ResumeRequest request) {
        String id = UUID.randomUUID().toString();
        String title = required(request.title(), "简历标题", 200);
        String content = required(request.content(), "简历内容", 40_000);
        jdbc.sql("INSERT INTO resumes (id, user_id, title, content) VALUES (:id, :userId, :title, :content)")
            .param("id", id).param("userId", userId).param("title", title).param("content", content).update();
        return new Resume(id, title, content);
    }

    public Resume updateResume(String userId, String id, ResumeRequest request) {
        String title = required(request.title(), "简历标题", 200);
        String content = required(request.content(), "简历内容", 40_000);
        if (jdbc.sql("UPDATE resumes SET title = :title, content = :content, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).param("title", title).param("content", content).update() == 0) throw notFound();
        return new Resume(id, title, content);
    }

    public void deleteResume(String userId, String id) { delete("resumes", userId, id); }

    public List<JobDescription> jobDescriptions(String userId) {
        return jdbc.sql("SELECT id, company, role, content FROM job_descriptions WHERE user_id = :userId ORDER BY updated_at DESC")
            .param("userId", userId).query(JobDescription.class).list();
    }

    public JobDescription jobDescription(String userId, String id) {
        return jdbc.sql("SELECT id, company, role, content FROM job_descriptions WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query(JobDescription.class).optional().orElseThrow(MaterialService::notFound);
    }

    public JobDescription createJobDescription(String userId, JobDescriptionRequest request) {
        String id = UUID.randomUUID().toString();
        String company = required(request.company(), "公司", 200); String role = required(request.role(), "岗位", 200); String content = required(request.content(), "JD 内容", 40_000);
        jdbc.sql("INSERT INTO job_descriptions (id, user_id, company, role, content) VALUES (:id, :userId, :company, :role, :content)")
            .param("id", id).param("userId", userId).param("company", company).param("role", role).param("content", content).update();
        return new JobDescription(id, company, role, content);
    }

    public JobDescription updateJobDescription(String userId, String id, JobDescriptionRequest request) {
        String company = required(request.company(), "公司", 200); String role = required(request.role(), "岗位", 200); String content = required(request.content(), "JD 内容", 40_000);
        if (jdbc.sql("UPDATE job_descriptions SET company = :company, role = :role, content = :content, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).param("company", company).param("role", role).param("content", content).update() == 0) throw notFound();
        return new JobDescription(id, company, role, content);
    }

    public void deleteJobDescription(String userId, String id) { delete("job_descriptions", userId, id); }

    public List<ProjectEvidenceCard> evidenceCards(String userId) {
        return jdbc.sql("SELECT id, project_name, background_and_role, goal_and_metrics, constraints_and_tradeoffs, personal_contribution, result_and_retrospective, applicable_question_types FROM project_evidence_cards WHERE user_id = :userId ORDER BY updated_at DESC")
            .param("userId", userId).query(ProjectEvidenceCard.class).list();
    }

    public ProjectEvidenceCard evidenceCard(String userId, String id) {
        return jdbc.sql("SELECT id, project_name, background_and_role, goal_and_metrics, constraints_and_tradeoffs, personal_contribution, result_and_retrospective, applicable_question_types FROM project_evidence_cards WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query(ProjectEvidenceCard.class).optional().orElseThrow(MaterialService::notFound);
    }

    public ProjectEvidenceCard createEvidenceCard(String userId, EvidenceCardRequest request) {
        String id = UUID.randomUUID().toString();
        ProjectEvidenceCard card = card(id, request);
        jdbc.sql("INSERT INTO project_evidence_cards (id, user_id, project_name, background_and_role, goal_and_metrics, constraints_and_tradeoffs, personal_contribution, result_and_retrospective, applicable_question_types) VALUES (:id, :userId, :projectName, :backgroundAndRole, :goalAndMetrics, :constraintsAndTradeoffs, :personalContribution, :resultAndRetrospective, :applicableQuestionTypes)")
            .paramSource(cardParameters(card, userId)).update();
        return card;
    }

    public ProjectEvidenceCard updateEvidenceCard(String userId, String id, EvidenceCardRequest request) {
        ProjectEvidenceCard card = card(id, request);
        if (jdbc.sql("UPDATE project_evidence_cards SET project_name = :projectName, background_and_role = :backgroundAndRole, goal_and_metrics = :goalAndMetrics, constraints_and_tradeoffs = :constraintsAndTradeoffs, personal_contribution = :personalContribution, result_and_retrospective = :resultAndRetrospective, applicable_question_types = :applicableQuestionTypes, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .paramSource(cardParameters(card, userId)).update() == 0) throw notFound();
        return card;
    }

    public void deleteEvidenceCard(String userId, String id) { delete("project_evidence_cards", userId, id); }

    public List<InterviewPackage> interviewPackages(String userId) {
        return jdbc.sql("SELECT id, company, role, interview_round, resume_file_id, job_description_id FROM interview_packages WHERE user_id = :userId ORDER BY updated_at DESC")
            .param("userId", userId).query((rs, row) -> packageFromRow(userId, rs.getString("id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("resume_file_id"), rs.getString("job_description_id"))).list();
    }

    public InterviewPackage interviewPackage(String userId, String id) {
        return jdbc.sql("SELECT id, company, role, interview_round, resume_file_id, job_description_id FROM interview_packages WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> packageFromRow(userId, rs.getString("id"), rs.getString("company"), rs.getString("role"), rs.getString("interview_round"), rs.getString("resume_file_id"), rs.getString("job_description_id"))).optional().orElseThrow(MaterialService::notFound);
    }

    @Transactional
    public InterviewPackage createInterviewPackage(String userId, InterviewPackageRequest request) {
        String id = UUID.randomUUID().toString();
        InterviewPackage result = packageFromRequest(id, userId, request);
        jdbc.sql("INSERT INTO interview_packages (id, user_id, company, role, interview_round, resume_file_id, job_description_id) VALUES (:id, :userId, :company, :role, :interviewRound, :resumeFileId, :jobDescriptionId)")
            .param("id", id).param("userId", userId).param("company", result.company()).param("role", result.role()).param("interviewRound", result.interviewRound()).param("resumeFileId", result.resumeFileId()).param("jobDescriptionId", result.jobDescriptionId()).update();
        replaceEvidenceCards(id, result.evidenceCardIds());
        return result;
    }

    @Transactional
    public InterviewPackage updateInterviewPackage(String userId, String id, InterviewPackageRequest request) {
        InterviewPackage result = packageFromRequest(id, userId, request);
        if (jdbc.sql("UPDATE interview_packages SET company = :company, role = :role, interview_round = :interviewRound, resume_file_id = :resumeFileId, job_description_id = :jobDescriptionId, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).param("company", result.company()).param("role", result.role()).param("interviewRound", result.interviewRound()).param("resumeFileId", result.resumeFileId()).param("jobDescriptionId", result.jobDescriptionId()).update() == 0) throw notFound();
        replaceEvidenceCards(id, result.evidenceCardIds());
        return result;
    }

    public void deleteInterviewPackage(String userId, String id) { delete("interview_packages", userId, id); }

    private InterviewPackage packageFromRequest(String id, String userId, InterviewPackageRequest request) {
        String resumeFileId = required(request.resumeFileId(), "简历文件", 200); String jobDescriptionId = required(request.jobDescriptionId(), "JD", 200);
        resumeFiles.metadata(userId, resumeFileId);
        jobDescription(userId, jobDescriptionId);
        List<String> cardIds = request.evidenceCardIds() == null ? List.of() : request.evidenceCardIds().stream().distinct().toList();
        cardIds.forEach(cardId -> evidenceCard(userId, cardId));
        return new InterviewPackage(id, required(request.company(), "公司", 200), required(request.role(), "岗位", 200), required(request.interviewRound(), "面试轮次", 200), resumeFileId, jobDescriptionId, cardIds);
    }

    private InterviewPackage packageFromRow(String userId, String id, String company, String role, String interviewRound, String resumeFileId, String jobDescriptionId) {
        List<String> cardIds = jdbc.sql("SELECT evidence_card_id FROM interview_package_evidence_cards WHERE interview_package_id = :id ORDER BY evidence_card_id")
            .param("id", id).query(String.class).list();
        return new InterviewPackage(id, company, role, interviewRound, resumeFileId, jobDescriptionId, cardIds);
    }

    private void replaceEvidenceCards(String packageId, List<String> cardIds) {
        jdbc.sql("DELETE FROM interview_package_evidence_cards WHERE interview_package_id = :id").param("id", packageId).update();
        cardIds.forEach(cardId -> jdbc.sql("INSERT INTO interview_package_evidence_cards (interview_package_id, evidence_card_id) VALUES (:packageId, :cardId)")
            .param("packageId", packageId).param("cardId", cardId).update());
    }

    private ProjectEvidenceCard card(String id, EvidenceCardRequest request) {
        return new ProjectEvidenceCard(id, required(request.projectName(), "项目名称", 200), required(request.backgroundAndRole(), "背景与角色", 12_000), required(request.goalAndMetrics(), "目标与指标", 12_000), required(request.constraintsAndTradeoffs(), "约束与取舍", 12_000), required(request.personalContribution(), "个人贡献", 12_000), required(request.resultAndRetrospective(), "结果与复盘", 12_000), required(request.applicableQuestionTypes(), "适用问题类型", 2_000));
    }

    private Map<String, String> cardParameters(ProjectEvidenceCard card, String userId) {
        return Map.of("id", card.id(), "userId", userId, "projectName", card.projectName(), "backgroundAndRole", card.backgroundAndRole(), "goalAndMetrics", card.goalAndMetrics(), "constraintsAndTradeoffs", card.constraintsAndTradeoffs(), "personalContribution", card.personalContribution(), "resultAndRetrospective", card.resultAndRetrospective(), "applicableQuestionTypes", card.applicableQuestionTypes());
    }

    private void delete(String table, String userId, String id) {
        if (jdbc.sql("DELETE FROM " + table + " WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).update() == 0) throw notFound();
    }

    private static String required(String value, String label) {
        return required(value, label, Integer.MAX_VALUE);
    }

    private static String required(String value, String label, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空。");
        String result = value.trim();
        if (result.length() > maximum) throw new IllegalArgumentException(label + "过长，请控制在 " + maximum + " 个字符以内。");
        return result;
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static NoSuchElementException notFound() { return new NoSuchElementException("资源不存在或无权访问。"); }
}

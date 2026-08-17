package com.interviewagent.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewagent.material.MaterialService;
import com.interviewagent.material.ResumeFileService;
import com.interviewagent.interview.InterviewService;
import com.interviewagent.weakness.WeaknessApi.TrainingTaskRequest;
import com.interviewagent.weakness.WeaknessService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Java-side tool gateway: every query/write is still scoped by the JWT subject supplied by Java. */
@Service
public class AgentToolService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ResumeFileService resumeFiles;
    private final MaterialService materials;
    private final InterviewService interviews;
    private final WeaknessService weaknesses;

    public AgentToolService(JdbcClient jdbc, ObjectMapper json, ResumeFileService resumeFiles, MaterialService materials, InterviewService interviews, WeaknessService weaknesses) {
        this.jdbc = jdbc; this.json = json; this.resumeFiles = resumeFiles; this.materials = materials; this.interviews = interviews; this.weaknesses = weaknesses;
    }

    @Transactional
    public Object execute(String userId, String name, JsonNode arguments) {
        try {
            return switch (name) {
                case "list_resources" -> listResources(userId, required(arguments, "resource_type"));
                case "get_resource" -> getResource(userId, required(arguments, "resource_type"), required(arguments, "id"));
                case "create_training_task" -> createTrainingTask(userId, arguments);
                default -> throw new IllegalArgumentException("不支持的工具：" + name);
            };
        } catch (IllegalArgumentException | NoSuchElementException exception) {
            return Map.of("error", exception.getMessage());
        }
    }

    private Object listResources(String userId, String type) {
        return switch (type) {
            case "interview_package" -> materials.interviewPackages(userId);
            case "resume" -> materials.resumes(userId);
            case "resume_file" -> resumeFiles.files(userId);
            case "job_description" -> materials.jobDescriptions(userId);
            case "evidence_card" -> materials.evidenceCards(userId);
            case "interview" -> interviews.list(userId);
            case "weakness" -> weaknesses.weaknesses(userId);
            case "training_task" -> weaknesses.tasks(userId);
            default -> throw new IllegalArgumentException("资料类型无效。");
        };
    }

    private Object getResource(String userId, String type, String id) {
        return switch (type) {
            case "interview_package" -> materials.interviewPackage(userId, id);
            case "resume" -> materials.resume(userId, id);
            case "resume_file" -> resumeFile(userId, id);
            case "job_description" -> materials.jobDescription(userId, id);
            case "evidence_card" -> materials.evidenceCard(userId, id);
            case "interview" -> interviews.get(userId, id);
            case "weakness" -> weaknesses.weakness(userId, id);
            case "training_task" -> weaknesses.task(userId, id);
            default -> throw new IllegalArgumentException("资料类型无效。");
        };
    }

    private Map<String, Object> resumeFile(String userId, String id) {
        var metadata = resumeFiles.metadata(userId, id);
        var parsed = resumeFiles.parsedText(userId, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metadata", metadata); result.put("parsedStatus", parsed.status()); result.put("parsedText", parsed.text());
        result.put("parsedTruncated", parsed.truncated()); result.put("parsedError", parsed.error());
        return result;
    }

    private Object createTrainingTask(String userId, JsonNode arguments) {
        String title = required(arguments, "title"), tag = required(arguments, "weakness_tag"), action = required(arguments, "action");
        String interviewId = optional(arguments, "source_interview_id"), reviewId = optional(arguments, "source_review_report_id");
        String existingId = existingTrainingTask(userId, title, tag, action, interviewId, reviewId);
        if (existingId != null) return Map.of("status", "already_exists", "task", weaknesses.task(userId, existingId));
        return Map.of("status", "created", "task", weaknesses.create(userId, new TrainingTaskRequest(title, tag, action, "NOT_STARTED", interviewId, reviewId)));
    }

    private String existingTrainingTask(String userId, String title, String tag, String action, String interviewId, String reviewId) {
        var query = jdbc.sql("SELECT id FROM training_tasks WHERE user_id = :userId AND title = :title AND weakness_tag = :tag AND action = :action AND " + (reviewId != null ? "source_review_report_id = :sourceId" : interviewId != null ? "source_review_report_id IS NULL AND source_interview_id = :sourceId" : "source_review_report_id IS NULL AND source_interview_id IS NULL") + " ORDER BY created_at DESC LIMIT 1")
            .param("userId", userId).param("title", title).param("tag", tag).param("action", action);
        if (reviewId != null || interviewId != null) query = query.param("sourceId", reviewId != null ? reviewId : interviewId);
        return query.query(String.class).optional().orElse(null);
    }

    private static String required(JsonNode arguments, String name) {
        String value = optional(arguments, name); if (value == null) throw new IllegalArgumentException("工具参数 " + name + " 不能为空。"); return value;
    }
    private static String optional(JsonNode arguments, String name) { String value = arguments.path(name).asText("").trim(); return value.isBlank() ? null : value; }
}

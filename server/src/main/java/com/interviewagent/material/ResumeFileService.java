package com.interviewagent.material;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ResumeFileService {
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private final JdbcClient jdbc;
    private final ResumeFileStorage storage;
    private final ResumeTextExtractor extractor;

    ResumeFileService(JdbcClient jdbc, ResumeFileStorage storage, ResumeTextExtractor extractor) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.extractor = extractor;
    }

    public List<ResumeFile> files(String userId) {
        return jdbc.sql("SELECT id, original_filename, content_type, size_bytes, parsed_status, parsed_truncated, created_at FROM resume_files WHERE user_id = :userId ORDER BY created_at DESC")
            .param("userId", userId).query(this::resumeFile).list();
    }

    public ResumeFile upload(String userId, MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw new IllegalArgumentException("请选择简历文件。");
        if (upload.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("简历文件不能超过 10 MiB。");
        String filename = filename(upload.getOriginalFilename());
        String extension = extension(filename);
        byte[] content;
        try {
            content = upload.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取上传的简历文件。");
        }
        if (content.length > MAX_FILE_SIZE) throw new IllegalArgumentException("简历文件不能超过 10 MiB。");
        String contentType = detectedContentType(content, extension);
        ParsedResume parsed = extract(contentType, content);
        String id = UUID.randomUUID().toString();
        String objectPath = "resumes/" + id + "." + extension;
        storage.upload(objectPath, contentType, content);
        try {
            jdbc.sql("INSERT INTO resume_files (id, user_id, original_filename, content_type, size_bytes, object_path, parsed_text, parsed_status, parsed_truncated, parsed_error, parsed_at) VALUES (:id, :userId, :filename, :contentType, :sizeBytes, :objectPath, :text, :status, :truncated, :error, CURRENT_TIMESTAMP)")
                .param("id", id).param("userId", userId).param("filename", filename).param("contentType", contentType).param("sizeBytes", (long) content.length).param("objectPath", objectPath).param("text", parsed.text()).param("status", parsed.status()).param("truncated", parsed.truncated()).param("error", parsed.error()).update();
        } catch (RuntimeException exception) {
            try { storage.delete(objectPath); } catch (RuntimeException ignored) { }
            throw exception;
        }
        return file(userId, id).file();
    }

    public byte[] content(String userId, String id) {
        return storage.download(file(userId, id).objectPath());
    }

    public ResumeFile metadata(String userId, String id) {
        return file(userId, id).file();
    }

    public ParsedResume parsedText(String userId, String id) {
        StoredResumeFile stored = file(userId, id);
        if (!"PENDING".equals(stored.status())) return new ParsedResume(stored.text(), stored.status(), stored.truncated(), stored.error());
        byte[] bytes;
        try {
            bytes = storage.download(stored.objectPath());
        } catch (RuntimeException exception) {
            return new ParsedResume(null, "PENDING", false, "解析暂不可用，请稍后重试。");
        }
        ParsedResume parsed = extract(stored.file().contentType(), bytes);
        jdbc.sql("UPDATE resume_files SET parsed_text = :text, parsed_status = :status, parsed_truncated = :truncated, parsed_error = :error, parsed_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
            .param("text", parsed.text()).param("status", parsed.status()).param("truncated", parsed.truncated()).param("error", parsed.error()).param("id", id).param("userId", userId).update();
        return parsed;
    }

    public void delete(String userId, String id) {
        StoredResumeFile file = file(userId, id);
        storage.delete(file.objectPath());
        jdbc.sql("DELETE FROM resume_files WHERE id = :id AND user_id = :userId").param("id", id).param("userId", userId).update();
    }

    private StoredResumeFile file(String userId, String id) {
        return jdbc.sql("SELECT id, original_filename, content_type, size_bytes, object_path, parsed_text, parsed_status, parsed_truncated, parsed_error, created_at FROM resume_files WHERE id = :id AND user_id = :userId")
            .param("id", id).param("userId", userId).query((rs, row) -> new StoredResumeFile(resumeFile(rs, row), rs.getString("object_path"), rs.getString("parsed_text"), rs.getString("parsed_status"), rs.getBoolean("parsed_truncated"), rs.getString("parsed_error"))).optional()
            .orElseThrow(() -> new NoSuchElementException("资源不存在或无权访问。"));
    }

    private ResumeFile resumeFile(ResultSet rs, int row) throws SQLException {
        return new ResumeFile(rs.getString("id"), rs.getString("original_filename"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("parsed_status"), rs.getBoolean("parsed_truncated"), rs.getTimestamp("created_at").toInstant());
    }

    private ParsedResume extract(String contentType, byte[] content) {
        try {
            ResumeTextExtractor.ExtractedText text = extractor.extract(contentType, content);
            return new ParsedResume(text.text(), "READY", text.truncated(), null);
        } catch (Exception exception) {
            return new ParsedResume(null, "FAILED", false, "无法提取可用正文；扫描件或受保护文件请补充可复制的文字版简历。");
        }
    }

    private static String filename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) throw new IllegalArgumentException("文件名不能为空。");
        String value = originalFilename.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).trim().replaceAll("[\\r\\n]", "");
        if (value.isBlank() || value.length() > 255) throw new IllegalArgumentException("文件名无效。");
        return value;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot < 1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("pdf") && !extension.equals("doc") && !extension.equals("docx")) throw new IllegalArgumentException("仅支持 PDF、DOC 或 DOCX 简历文件。");
        return extension;
    }

    private static String detectedContentType(byte[] content, String extension) {
        String detected = isPdf(content) ? "application/pdf" : isDoc(content) ? "application/msword" : isDocx(content) ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : null;
        if (detected == null || !detected.endsWith(extension.equals("docx") ? "document" : extension.equals("doc") ? "msword" : "pdf")) {
            throw new IllegalArgumentException("文件扩展名与实际文件类型不匹配。");
        }
        return detected;
    }

    private static boolean isPdf(byte[] content) {
        return content.length >= 5 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-';
    }

    private static boolean isDoc(byte[] content) {
        byte[] header = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        if (content.length < header.length) return false;
        for (int i = 0; i < header.length; i++) if (content[i] != header[i]) return false;
        return new String(content, StandardCharsets.UTF_16LE).contains("WordDocument");
    }

    private static boolean isDocx(byte[] content) {
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K') return false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            boolean document = false;
            boolean wordContentType = false;
            for (int entries = 0; entries < 128; entries++) {
                var entry = zip.getNextEntry();
                if (entry == null) return false;
                if (entry.getName().equals("word/document.xml")) document = true;
                if (entry.getName().equals("[Content_Types].xml")) {
                    String types = new String(zip.readNBytes(64 * 1024), StandardCharsets.UTF_8);
                    wordContentType = types.contains("wordprocessingml.document.main+xml");
                }
                if (document && wordContentType) return true;
            }
        } catch (IOException exception) {
            return false;
        }
        return false;
    }

    public record ParsedResume(String text, String status, boolean truncated, String error) {}
    private record StoredResumeFile(ResumeFile file, String objectPath, String text, String status, boolean truncated, String error) {}
}

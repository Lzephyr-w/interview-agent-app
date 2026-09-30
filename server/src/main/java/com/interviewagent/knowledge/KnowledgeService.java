package com.interviewagent.knowledge;

import com.interviewagent.material.ResumeFileStorage;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final JdbcClient jdbc;
    private final ResumeFileStorage storage;
    private final KnowledgeParser parser;

    public KnowledgeService(JdbcClient jdbc, ResumeFileStorage storage, KnowledgeParser parser) {
        this.jdbc = jdbc; this.storage = storage; this.parser = parser;
    }

    public List<Category> categories(String user) {
        return jdbc.sql("SELECT id,name FROM knowledge_categories WHERE user_id=:user ORDER BY name")
            .param("user", user).query(Category.class).list();
    }

    public Category createCategory(String user, String name) {
        String value = categoryName(name);
        if (jdbc.sql("SELECT COUNT(*) FROM knowledge_categories WHERE user_id=:user AND name=:name")
            .param("user", user).param("name", value).query(Integer.class).single() > 0)
            throw new IllegalArgumentException("类别名称已存在。");
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO knowledge_categories(id,user_id,name) VALUES(:id,:user,:name)")
            .param("id", id).param("user", user).param("name", value).update();
        return new Category(id, value);
    }

    public Category renameCategory(String user, String id, String name) {
        category(user, id);
        String value = categoryName(name);
        if (jdbc.sql("SELECT COUNT(*) FROM knowledge_categories WHERE user_id=:user AND name=:name AND id<>:id")
            .param("user", user).param("name", value).param("id", id).query(Integer.class).single() > 0)
            throw new IllegalArgumentException("类别名称已存在。");
        jdbc.sql("UPDATE knowledge_categories SET name=:name WHERE id=:id AND user_id=:user")
            .param("name", value).param("id", id).param("user", user).update();
        return new Category(id, value);
    }

    public void deleteCategory(String user, String id) {
        category(user, id);
        if (jdbc.sql("SELECT COUNT(*) FROM knowledge_documents WHERE user_id=:user AND category_id=:id")
            .param("user", user).param("id", id).query(Integer.class).single() > 0)
            throw new IllegalArgumentException("请先移动或删除该类别的文档。");
        jdbc.sql("DELETE FROM knowledge_categories WHERE id=:id AND user_id=:user")
            .param("id", id).param("user", user).update();
    }

    public List<Document> documents(String user) {
        return jdbc.sql("SELECT id,category_id,original_filename,format,size_bytes,segment_count,created_at FROM knowledge_documents WHERE user_id=:user ORDER BY created_at DESC")
            .param("user", user).query((rs, row) -> new Document(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getObject(7, OffsetDateTime.class))).list();
    }

    public Document document(String user, String id) {
        return jdbc.sql("SELECT id,category_id,original_filename,format,size_bytes,segment_count,created_at FROM knowledge_documents WHERE user_id=:user AND id=:id")
            .param("user", user).param("id", id).query((rs, row) -> new Document(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getObject(7, OffsetDateTime.class)))
            .optional().orElseThrow(KnowledgeService::notFound);
    }

    @Transactional
    public Document upload(String user, String categoryId, MultipartFile file) {
        category(user, categoryId);
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim().replaceAll("[\\r\\n]", "");
        if (name.isBlank() || name.length() > 255) throw new IllegalArgumentException("文件名无效。");
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("md", "xlsx", "doc", "docx").contains(extension)) throw new IllegalArgumentException("仅支持 MD、XLSX、DOC、DOCX 知识库文件。");
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new IllegalArgumentException("文件不能为空且不能超过 10 MiB。");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException error) { throw new IllegalArgumentException("文件读取失败。", error); }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("文件不能为空且不能超过 10 MiB。");
        List<KnowledgeParser.Segment> segments = parser.parse(extension, bytes);
        String id = UUID.randomUUID().toString();
        String path = "knowledge/" + id + "." + extension;
        String type = switch (extension) {
            case "md" -> "text/markdown; charset=utf-8";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "doc" -> "application/msword";
            default -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        };
        storage.upload(path, type, bytes);
        try {
            jdbc.sql("INSERT INTO knowledge_documents(id,user_id,category_id,original_filename,format,size_bytes,object_path,segment_count) VALUES(:id,:user,:category,:name,:format,:size,:path,:count)")
                .param("id", id).param("user", user).param("category", categoryId).param("name", name).param("format", extension).param("size", bytes.length).param("path", path).param("count", segments.size()).update();
            for (int index = 0; index < segments.size(); index++) {
                var segment = segments.get(index);
                jdbc.sql("INSERT INTO knowledge_segments(id,document_id,position,kind,location,content,original_answer,reference_answer,reminder) VALUES(:id,:document,:position,:kind,:location,:content,:original,:answer,:reminder)")
                    .param("id", UUID.randomUUID().toString()).param("document", id).param("position", index).param("kind", segment.kind())
                    .param("location", segment.location()).param("content", segment.content()).param("original", segment.originalAnswer())
                    .param("answer", segment.answer()).param("reminder", segment.reminder()).update();
            }
        } catch (RuntimeException error) {
            try { storage.delete(path); } catch (RuntimeException ignored) { }
            throw error;
        }
        return document(user, id);
    }

    public void move(String user, String id, String categoryId) {
        document(user, id); category(user, categoryId);
        jdbc.sql("UPDATE knowledge_documents SET category_id=:category WHERE id=:id AND user_id=:user")
            .param("category", categoryId).param("id", id).param("user", user).update();
    }

    public byte[] content(String user, String id) { return storage.download(path(user, id)); }

    @Transactional
    public void delete(String user, String id) {
        String path = path(user, id);
        jdbc.sql("DELETE FROM knowledge_documents WHERE id=:id AND user_id=:user")
            .param("id", id).param("user", user).update();
        storage.delete(path);
    }

    public List<String> selectedDocuments(String user, List<String> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty() || categoryIds.size() > 10) throw new IllegalArgumentException("请选择 1–10 个知识库类别。");
        List<String> unique = categoryIds.stream().distinct().toList();
        if (unique.size() != categoryIds.size()) throw new IllegalArgumentException("知识库类别不能重复。");
        for (String id : unique) category(user, id);
        for (String id : unique) if (jdbc.sql("SELECT COUNT(*) FROM knowledge_documents WHERE user_id=:user AND category_id=:id")
            .param("user",user).param("id",id).query(Integer.class).single()==0)
            throw new IllegalArgumentException("所选类别中有空类别，请先上传文档。");
        List<String> ids = jdbc.sql("SELECT id FROM knowledge_documents WHERE user_id=:user AND category_id IN (:categories) ORDER BY id")
            .param("user", user).param("categories", unique).query(String.class).list();
        if (ids.size() > 20) throw new IllegalArgumentException("一次最多选择 20 份知识库文档，请精简类别。");
        return ids;
    }

    public Source retrieve(String user, List<String> documentIds, String query, Set<String> used) {
        if (documentIds == null || documentIds.isEmpty()) throw new IllegalArgumentException("所选知识库缺少相关内容，请重新选择类别。");
        // ponytail: scan selected segments in memory; add an index only when real corpus size makes this slow.
        var candidates = jdbc.sql("SELECT s.id,d.id,d.original_filename,s.location,s.content,s.reference_answer,s.reminder FROM knowledge_segments s JOIN knowledge_documents d ON d.id=s.document_id WHERE d.user_id=:user AND d.id IN (:documents) ORDER BY d.id,s.position")
            .param("user", user).param("documents", documentIds)
            .query((rs, row) -> new Source(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7))).list();
        Set<String> terms = grams(query);
        Source best = null; int bestScore = -1;
        for (Source source : candidates) {
            if (used.contains(source.id())) continue;
            Set<String> match = grams(source.title() + source.text());
            match.retainAll(terms);
            int score = match.size() + (source.location().matches("^[SA][级級].*") ? 2 : 0);
            if (score > bestScore) { best = source; bestScore = score; }
        }
        if (best == null) throw new IllegalArgumentException("所选知识库缺少更多可用题目或段落，请重新选择类别。");
        return best;
    }

    private static Set<String> grams(String text) {
        String clean = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> result = new HashSet<>();
        for (int i = 1; i < clean.length(); i++) result.add(clean.substring(i - 1, i + 1));
        return result;
    }

    private void category(String user, String id) {
        if (id == null || jdbc.sql("SELECT COUNT(*) FROM knowledge_categories WHERE id=:id AND user_id=:user")
            .param("id", id).param("user", user).query(Integer.class).single() == 0) throw notFound();
    }

    private String path(String user, String id) {
        return jdbc.sql("SELECT object_path FROM knowledge_documents WHERE id=:id AND user_id=:user")
            .param("id", id).param("user", user).query(String.class).optional().orElseThrow(KnowledgeService::notFound);
    }

    private static NoSuchElementException notFound() { return new NoSuchElementException("资源不存在或无权访问。"); }
    private static String categoryName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isBlank() || value.length() > 80) throw new IllegalArgumentException("类别名称应为 1–80 个字符。");
        return value;
    }

    public record Category(String id, String name) {}
    public record Document(String id, String categoryId, String originalFilename, String format, long sizeBytes, int segmentCount, OffsetDateTime createdAt) {}
    public record Source(String id, String documentId, String title, String location, String text, String answer, String reminder) {}
}

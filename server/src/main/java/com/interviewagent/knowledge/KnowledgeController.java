package com.interviewagent.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    KnowledgeController(KnowledgeService service) { this.service = service; }

    @GetMapping("/categories") List<KnowledgeService.Category> categories(@AuthenticationPrincipal Jwt jwt) { return service.categories(jwt.getSubject()); }
    @PostMapping("/categories") @ResponseStatus(HttpStatus.CREATED) KnowledgeService.Category createCategory(@AuthenticationPrincipal Jwt jwt, @RequestBody CategoryRequest request) { return service.createCategory(jwt.getSubject(), request.name()); }
    @PutMapping("/categories/{id}") KnowledgeService.Category renameCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody CategoryRequest request) { return service.renameCategory(jwt.getSubject(), id, request.name()); }
    @DeleteMapping("/categories/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.deleteCategory(jwt.getSubject(), id); }

    @GetMapping("/documents") List<KnowledgeService.Document> documents(@AuthenticationPrincipal Jwt jwt) { return service.documents(jwt.getSubject()); }
    @GetMapping("/documents/{id}") KnowledgeService.Document document(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.document(jwt.getSubject(), id); }
    @PostMapping(path="/documents", consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    KnowledgeService.Document upload(@AuthenticationPrincipal Jwt jwt, @RequestParam String categoryId, @RequestParam MultipartFile file) { return service.upload(jwt.getSubject(), categoryId, file); }
    @PutMapping("/documents/{id}/category") @ResponseStatus(HttpStatus.NO_CONTENT)
    void move(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody MoveRequest request) { service.move(jwt.getSubject(), id, request.categoryId()); }
    @GetMapping("/documents/{id}/content") ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        var file = service.document(jwt.getSubject(), id);
        byte[] bytes = service.content(jwt.getSubject(), id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(bytes.length).cacheControl(CacheControl.noStore())
            .header("Content-Disposition", ContentDisposition.attachment().filename(file.originalFilename(), StandardCharsets.UTF_8).build().toString()).body(bytes);
    }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.delete(jwt.getSubject(), id); }

    record CategoryRequest(String name) {}
    record MoveRequest(String categoryId) {}
}

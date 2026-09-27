package com.interviewagent.aimock;

import static com.interviewagent.aimock.AiMockInterviewApi.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/ai-mock-interviews")
class AiMockInterviewController {
    private final AiMockInterviewService service;
    AiMockInterviewController(AiMockInterviewService service) { this.service = service; }
    @GetMapping Session list(@AuthenticationPrincipal Jwt jwt) { return service.active(jwt.getSubject()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) Session create(@AuthenticationPrincipal Jwt jwt, @RequestBody StartRequest request) { return service.create(jwt.getSubject(), request); }
    @GetMapping("/{id}") Session get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.get(jwt.getSubject(), id); }
    @PostMapping("/{id}/questions/{questionId}/start-answer") AnswerStart startAnswer(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId) { return service.startAnswer(jwt.getSubject(), id, questionId); }
    @PostMapping("/{id}/questions/{questionId}/skip-answer") Session skipAnswer(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId) { return service.skipAnswer(jwt.getSubject(), id, questionId); }
    @PostMapping("/{id}/questions/{questionId}/expire") Session expire(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId) { return service.expire(jwt.getSubject(), id, questionId); }
    @PostMapping("/{id}/questions/{questionId}/audio") Session audio(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @RequestParam("file") MultipartFile file) { return service.audio(jwt.getSubject(), id, questionId, file); }
    @PostMapping("/{id}/questions/{questionId}/audio-uploads") AudioUpload beginAudioUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @RequestBody AudioUploadRequest request) { return service.beginAudioUpload(jwt.getSubject(), id, questionId, request); }
    @GetMapping("/{id}/questions/{questionId}/audio-uploads/{uploadId}") AudioUpload audioUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @PathVariable String uploadId) { return service.audioUpload(jwt.getSubject(), id, questionId, uploadId); }
    @PutMapping("/{id}/questions/{questionId}/audio-uploads/{uploadId}/parts/{partNo}") @ResponseStatus(HttpStatus.NO_CONTENT) void audioUploadPart(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @PathVariable String uploadId, @PathVariable int partNo, @RequestHeader("Content-Range") String range, @RequestHeader("X-Chunk-SHA256") String sha256, @RequestBody byte[] bytes) { service.audioUploadPart(jwt.getSubject(), id, questionId, uploadId, partNo, range, sha256, bytes); }
    @PostMapping("/{id}/questions/{questionId}/audio-uploads/{uploadId}/complete") Session completeAudioUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @PathVariable String uploadId) { return service.completeAudioUpload(jwt.getSubject(), id, questionId, uploadId); }
    @DeleteMapping("/{id}/questions/{questionId}/audio-uploads/{uploadId}") Session cancelAudioUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @PathVariable String uploadId) { return service.cancelAudioUpload(jwt.getSubject(), id, questionId, uploadId); }
    @PostMapping("/{id}/questions/{questionId}/confirm-answer") Session confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @RequestBody ConfirmRequest request) { return service.confirm(jwt.getSubject(), id, questionId, request); }
    @PostMapping("/{id}/finish") Session finish(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.finish(jwt.getSubject(), id); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.delete(jwt.getSubject(), id); }
}

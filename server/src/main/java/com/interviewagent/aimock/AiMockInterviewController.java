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
    @PostMapping("/{id}/questions/{questionId}/start-answer") Session startAnswer(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId) { return service.startAnswer(jwt.getSubject(), id, questionId); }
    @PostMapping("/{id}/questions/{questionId}/expire") Session expire(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId) { return service.expire(jwt.getSubject(), id, questionId); }
    @PostMapping("/{id}/questions/{questionId}/audio") Session audio(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @RequestParam("file") MultipartFile file) { return service.audio(jwt.getSubject(), id, questionId, file); }
    @PostMapping("/{id}/questions/{questionId}/confirm-answer") Session confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @RequestBody ConfirmRequest request) { return service.confirm(jwt.getSubject(), id, questionId, request); }
    @PostMapping("/{id}/finish") Session finish(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.finish(jwt.getSubject(), id); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.delete(jwt.getSubject(), id); }
}

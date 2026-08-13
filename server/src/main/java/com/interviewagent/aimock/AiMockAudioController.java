package com.interviewagent.aimock;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-mock-audio-assets")
class AiMockAudioController {
    private final AiMockInterviewService service;
    AiMockAudioController(AiMockInterviewService service) { this.service = service; }
    @GetMapping("/{id}/content") ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.content(jwt.getSubject(), id); }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.deleteAudio(jwt.getSubject(), id); }
}

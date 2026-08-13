package com.interviewagent.chat;

import static com.interviewagent.chat.AiConversationApi.*;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-conversations")
class AiConversationController {
    private final AiConversationService service;

    AiConversationController(AiConversationService service) { this.service = service; }

    @GetMapping List<ConversationSummary> list(@AuthenticationPrincipal Jwt jwt) { return service.list(jwt.getSubject()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) ConversationDetail create(@AuthenticationPrincipal Jwt jwt, @RequestBody ConversationRequest request) { return service.create(jwt.getSubject(), request); }
    @GetMapping("/{id}") ConversationDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.get(jwt.getSubject(), id); }
    @PostMapping("/{id}/messages") @ResponseStatus(HttpStatus.CREATED) ConversationDetail addMessage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody MessageRequest request) { return service.addMessage(jwt.getSubject(), id, request); }
    @PostMapping("/{id}/messages/{messageId}/reply") Message reply(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String messageId) { return service.reply(jwt.getSubject(), id, messageId); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.delete(jwt.getSubject(), id); }
}

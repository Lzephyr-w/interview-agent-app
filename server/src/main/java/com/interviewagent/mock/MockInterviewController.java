package com.interviewagent.mock;

import static com.interviewagent.mock.MockInterviewApi.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mock-interviews")
public class MockInterviewController {
    private final MockInterviewService service;

    MockInterviewController(MockInterviewService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MockInterview create(@AuthenticationPrincipal Jwt jwt, @RequestBody StartRequest request) {
        return service.create(jwt.getSubject(), request);
    }

    @GetMapping("/{id}")
    MockInterview get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.get(jwt.getSubject(), id);
    }

    @PostMapping("/{id}/answer")
    MockInterview answer(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody AnswerRequest request) {
        return service.answer(jwt.getSubject(), id, request);
    }

    @PostMapping("/{id}/skip")
    MockInterview skip(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody(required = false) AnswerRequest request) {
        return service.skip(jwt.getSubject(), id, request == null ? null : request.questionId());
    }

    @PostMapping("/{id}/finish")
    MockInterview finish(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.finish(jwt.getSubject(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        service.delete(jwt.getSubject(), id);
    }
}

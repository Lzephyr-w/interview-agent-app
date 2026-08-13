package com.interviewagent.interview;

import static com.interviewagent.interview.InterviewApi.*;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/interviews")
class InterviewController {
    private final InterviewService service;
    InterviewController(InterviewService service) { this.service = service; }

    @GetMapping List<InterviewSummary> list(@AuthenticationPrincipal Jwt jwt) { return service.list(jwt.getSubject()); }
    @GetMapping("/{id}") InterviewDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.get(jwt.getSubject(), id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) InterviewDetail create(@AuthenticationPrincipal Jwt jwt, @RequestBody InterviewRequest request) { return service.create(jwt.getSubject(), request); }
    @PutMapping("/{id}") InterviewDetail update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody InterviewRequest request) { return service.update(jwt.getSubject(), id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.delete(jwt.getSubject(), id); }
    @PostMapping("/{id}/questions") @ResponseStatus(HttpStatus.CREATED) InterviewQuestion createQuestion(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody QuestionRequest request) { return service.createQuestion(jwt.getSubject(), id, request); }
    @PutMapping("/{id}/questions/{questionId}") InterviewQuestion updateQuestion(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId, @RequestBody QuestionRequest request) { return service.updateQuestion(jwt.getSubject(), id, questionId, request); }
    @DeleteMapping("/{id}/questions/{questionId}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteQuestion(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId) { service.deleteQuestion(jwt.getSubject(), id, questionId); }
    @PostMapping("/{id}/segment-transcript") List<InterviewQuestion> segmentTranscript(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody TranscriptRequest request) { return service.segmentTranscript(jwt.getSubject(), id, request); }
    @PostMapping("/{id}/review") @ResponseStatus(HttpStatus.CREATED) ReviewReport review(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.review(jwt.getSubject(), id); }
    @DeleteMapping("/{id}/reviews/{reviewId}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteReview(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String reviewId) { service.deleteReview(jwt.getSubject(), id, reviewId); }
}

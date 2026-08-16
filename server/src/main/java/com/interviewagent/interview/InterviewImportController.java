package com.interviewagent.interview;

import static com.interviewagent.interview.InterviewApi.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/interview-imports")
class InterviewImportController {
    private final InterviewImportService service;
    InterviewImportController(InterviewImportService service) { this.service = service; }
    @PostMapping("/audio") @ResponseStatus(HttpStatus.CREATED) InterviewImport upload(@AuthenticationPrincipal Jwt jwt, @RequestParam(value = "interviewId", required = false) String interviewId, @RequestParam("file") MultipartFile file) { return service.upload(jwt.getSubject(), interviewId, file); }
    @GetMapping("/{id}") InterviewImport get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.get(jwt.getSubject(), id); }
    @PostMapping("/{id}/analyze") InterviewImport analyze(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.analyze(jwt.getSubject(), id); }
    @PostMapping("/{id}/confirm") InterviewDetail confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody ImportConfirmRequest request) { return service.confirm(jwt.getSubject(), id, request); }
}

package com.interviewagent.ai;

import static com.interviewagent.ai.AiTaskApi.Task;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-mock-tasks")
public class AiMockTaskController {
    private final AiMockTaskService tasks;

    public AiMockTaskController(AiMockTaskService tasks) { this.tasks = tasks; }

    @GetMapping("/{id}")
    Task get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return tasks.get(jwt.getSubject(), id); }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Task retry(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        tasks.retry(jwt.getSubject(), id);
        return tasks.get(jwt.getSubject(), id);
    }
}

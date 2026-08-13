package com.interviewagent.weakness;

import static com.interviewagent.weakness.WeaknessApi.*;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class WeaknessController {
    private final WeaknessService service;
    WeaknessController(WeaknessService service) { this.service = service; }

    @GetMapping("/weaknesses") List<WeaknessItem> weaknesses(@AuthenticationPrincipal Jwt jwt) { return service.weaknesses(jwt.getSubject()); }
    @GetMapping("/weaknesses/{tag}") WeaknessItem weakness(@AuthenticationPrincipal Jwt jwt, @PathVariable String tag) { return service.weakness(jwt.getSubject(), tag); }
    @GetMapping("/training-tasks") List<TrainingTask> tasks(@AuthenticationPrincipal Jwt jwt) { return service.tasks(jwt.getSubject()); }
    @GetMapping("/training-tasks/{id}") TrainingTask task(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.task(jwt.getSubject(), id); }
    @PostMapping("/training-tasks") @ResponseStatus(HttpStatus.CREATED) TrainingTask create(@AuthenticationPrincipal Jwt jwt, @RequestBody TrainingTaskRequest request) { return service.create(jwt.getSubject(), request); }
    @PutMapping("/training-tasks/{id}") TrainingTask update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody TrainingTaskRequest request) { return service.update(jwt.getSubject(), id, request); }
    @DeleteMapping("/training-tasks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.delete(jwt.getSubject(), id); }
}

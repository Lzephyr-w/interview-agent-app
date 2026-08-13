package com.interviewagent.dashboard;

import static com.interviewagent.dashboard.DashboardApi.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class DashboardController {
    private final DashboardService service;

    DashboardController(DashboardService service) { this.service = service; }

    @GetMapping("/dashboard") Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) { return service.dashboard(jwt.getSubject()); }
    @PostMapping("/sprint-checklist-items") @ResponseStatus(HttpStatus.CREATED) SprintItem create(@AuthenticationPrincipal Jwt jwt, @RequestBody SprintItemRequest request) { return service.create(jwt.getSubject(), request); }
    @PutMapping("/sprint-checklist-items/{id}") SprintItem update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody SprintItemRequest request) { return service.update(jwt.getSubject(), id, request); }
    @DeleteMapping("/sprint-checklist-items/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.delete(jwt.getSubject(), id); }
}

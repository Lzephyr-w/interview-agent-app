package com.interviewagent.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final UserProfileService userProfileService;
    public MeController(UserProfileService userProfileService) { this.userProfileService = userProfileService; }
    @GetMapping public CurrentUser me(@AuthenticationPrincipal Jwt jwt) { return userProfileService.upsert(jwt); }
}

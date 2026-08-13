package com.interviewagent.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = MeController.class, properties = "app.supabase.url=https://example.supabase.co")
@Import({com.interviewagent.config.SecurityConfig.class, com.interviewagent.common.RestAuthenticationEntryPoint.class})
class MeControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean UserProfileService userProfileService;
    @MockBean JwtDecoder jwtDecoder;

    @Test void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test void returnsOnlyJwtSubjectUser() throws Exception {
        given(userProfileService.upsert(any())).willReturn(new CurrentUser("8d741832-dc13-4ea4-bd83-492e4fc1d461", "user@example.com", "user@example.com"));
        mockMvc.perform(get("/api/v1/me").with(jwt().jwt(jwt -> jwt.subject("8d741832-dc13-4ea4-bd83-492e4fc1d461").claim("email", "user@example.com"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("8d741832-dc13-4ea4-bd83-492e4fc1d461")).andExpect(jsonPath("$.username").value("user@example.com"));
    }
}

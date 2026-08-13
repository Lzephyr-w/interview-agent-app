package com.interviewagent.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class UserProfileService {
    private final JdbcClient jdbcClient;
    public UserProfileService(JdbcClient jdbcClient) { this.jdbcClient = jdbcClient; }

    public CurrentUser upsert(Jwt jwt) {
        String userId = jwt.getSubject();
        String username = jwt.getClaimAsString("email");
        if (username == null || username.isBlank()) username = userId;
        if (jdbcClient.sql("UPDATE user_profiles SET username = :username, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId").param("username", username).param("userId", userId).update() == 0) {
            jdbcClient.sql("INSERT INTO user_profiles (user_id, username, display_name) VALUES (:userId, :username, :username)")
                .param("userId", userId).param("username", username).update();
        }
        return new CurrentUser(userId, username, username);
    }
}

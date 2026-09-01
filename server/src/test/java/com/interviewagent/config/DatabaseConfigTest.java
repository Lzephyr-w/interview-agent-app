package com.interviewagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DatabaseConfigTest {
    @Test
    void selectsSchemaForH2AndPostgres() {
        assertEquals("PUBLIC", DatabaseConfig.resolveSchema("jdbc:h2:mem:test", "interview_agent"));
        assertEquals("interview_agent", DatabaseConfig.resolveSchema(
            "jdbc:postgresql://localhost/postgres?sslmode=require&currentSchema=interview_agent", "PUBLIC"));
        assertEquals("custom_schema", DatabaseConfig.resolveSchema(
            "jdbc:postgresql://localhost/postgres", "custom_schema"));
    }
}

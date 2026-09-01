package com.interviewagent.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DatabaseConfig {
    @Bean
    FlywayConfigurationCustomizer flywaySchema(
        @Value("${spring.datasource.url}") String jdbcUrl,
        @Value("${APP_DATABASE_SCHEMA:}") String configuredSchema
    ) {
        String schema = resolveSchema(jdbcUrl, configuredSchema);
        return configuration -> configuration.defaultSchema(schema).schemas(schema).createSchemas(true);
    }

    static String resolveSchema(String jdbcUrl, String configuredSchema) {
        if (jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:")) return "PUBLIC";
        String currentSchema = queryParameter(jdbcUrl, "currentSchema");
        return currentSchema == null || currentSchema.isBlank()
            ? normalize(configuredSchema.isBlank() ? "PUBLIC" : configuredSchema)
            : normalize(currentSchema);
    }

    private static String queryParameter(String jdbcUrl, String name) {
        int queryStart = jdbcUrl.indexOf('?');
        if (queryStart < 0) return null;
        for (String pair : jdbcUrl.substring(queryStart + 1).split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equalsIgnoreCase(name)) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String normalize(String schema) {
        String value = schema.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid database schema name: " + value);
        }
        return value;
    }
}

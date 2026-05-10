package ru.team.novelbot.db;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class DatabaseInitializer {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        try {
            String sql = new ClassPathResource("db/schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isBlank()) {
                    jdbcTemplate.execute(trimmed);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось прочитать db/schema.sql.", ex);
        }
    }
}

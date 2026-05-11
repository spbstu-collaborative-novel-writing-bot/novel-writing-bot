package ru.team.novelbot.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminStatsService {
    private final JdbcTemplate jdbcTemplate;

    public AdminStatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", scalar("SELECT COUNT(*) FROM app_users"));
        result.put("admins", scalar("SELECT COUNT(*) FROM app_users WHERE role = 'ADMIN'"));
        result.put("novels", scalar("SELECT COUNT(*) FROM novels"));
        result.put("chapters", scalar("SELECT COUNT(*) FROM chapters"));
        result.put("authors", scalar("SELECT COUNT(*) FROM novel_authors"));
        result.put("owners", scalar("SELECT COUNT(*) FROM novel_authors WHERE author_type = 'OWNER'"));
        result.put("co_authors", scalar("SELECT COUNT(*) FROM novel_authors WHERE author_type = 'CO_AUTHOR'"));
        result.put("characters", scalar("SELECT COALESCE(SUM(LENGTH(text)), 0) FROM chapters"));
        result.put("words", totalWords());
        result.put("llm_requests", scalar("SELECT COUNT(*) FROM llm_requests"));
        result.put("llm_by_status", grouped("SELECT status AS group_key, COUNT(*) AS group_value FROM llm_requests GROUP BY status ORDER BY status"));
        result.put("llm_by_provider", grouped("SELECT provider AS group_key, COUNT(*) AS group_value FROM llm_requests GROUP BY provider ORDER BY provider"));
        result.put("llm_by_model", grouped("SELECT model AS group_key, COUNT(*) AS group_value FROM llm_requests GROUP BY model ORDER BY model"));
        return result;
    }

    public List<Map<String, Object>> users() {
        return jdbcTemplate.query(
                """
                SELECT u.chat_id, u.username, u.display_name, u.role, u.created_at,
                       COUNT(DISTINCT a.novel_id) AS accessible_novels,
                       SUM(CASE WHEN a.author_type = 'OWNER' THEN 1 ELSE 0 END) AS owned_novels
                FROM app_users u
                LEFT JOIN novel_authors a ON a.chat_id = u.chat_id
                GROUP BY u.chat_id, u.username, u.display_name, u.role, u.created_at
                ORDER BY u.created_at DESC, u.chat_id DESC
                """,
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chat_id", rs.getLong("chat_id"));
                    item.put("username", text(rs.getString("username")));
                    item.put("display_name", text(rs.getString("display_name")));
                    item.put("role", rs.getString("role"));
                    item.put("created_at", time(rs.getTimestamp("created_at")));
                    item.put("accessible_novels", rs.getInt("accessible_novels"));
                    item.put("owned_novels", rs.getInt("owned_novels"));
                    return item;
                }
        );
    }

    public List<Map<String, Object>> novels() {
        return jdbcTemplate.query(
                """
                SELECT n.id, n.title, n.genre, n.owner_chat_id, n.created_at, n.updated_at,
                       (SELECT COUNT(*) FROM novel_authors a WHERE a.novel_id = n.id) AS author_count,
                       (SELECT COUNT(*) FROM chapters c WHERE c.novel_id = n.id) AS chapter_count,
                       (SELECT COALESCE(SUM(LENGTH(c.text)), 0) FROM chapters c WHERE c.novel_id = n.id) AS character_count
                FROM novels n
                ORDER BY n.updated_at DESC, n.id DESC
                """,
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("title", rs.getString("title"));
                    item.put("genre", rs.getString("genre"));
                    item.put("creator_chat_id", rs.getLong("owner_chat_id"));
                    item.put("created_at", time(rs.getTimestamp("created_at")));
                    item.put("updated_at", time(rs.getTimestamp("updated_at")));
                    item.put("author_count", rs.getInt("author_count"));
                    item.put("chapter_count", rs.getInt("chapter_count"));
                    item.put("character_count", rs.getInt("character_count"));
                    return item;
                }
        );
    }

    public List<Map<String, Object>> llmRequests() {
        return jdbcTemplate.query(
                """
                SELECT id, chat_id, novel_id, chapter_id, request_type, status, provider, model,
                       error_message, created_at, updated_at, completed_at
                FROM llm_requests
                ORDER BY created_at DESC, id DESC
                LIMIT 50
                """,
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("chat_id", rs.getLong("chat_id"));
                    item.put("novel_id", rs.getLong("novel_id"));
                    long chapterId = rs.getLong("chapter_id");
                    item.put("chapter_id", rs.wasNull() ? "" : chapterId);
                    item.put("request_type", rs.getString("request_type"));
                    item.put("status", rs.getString("status"));
                    item.put("provider", rs.getString("provider"));
                    item.put("model", rs.getString("model"));
                    item.put("error_message", text(rs.getString("error_message")));
                    item.put("created_at", time(rs.getTimestamp("created_at")));
                    item.put("updated_at", time(rs.getTimestamp("updated_at")));
                    item.put("completed_at", time(rs.getTimestamp("completed_at")));
                    return item;
                }
        );
    }

    private long scalar(String sql) {
        Number value = jdbcTemplate.queryForObject(sql, Number.class);
        return value == null ? 0 : value.longValue();
    }

    private Map<String, Long> grouped(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, (RowCallbackHandler) rs ->
                result.put(text(rs.getString("group_key")), rs.getLong("group_value")));
        return result;
    }

    private int totalWords() {
        return jdbcTemplate.query("SELECT text FROM chapters", (rs, rowNum) -> rs.getString("text"))
                .stream()
                .mapToInt(TextTools::wordCount)
                .sum();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String time(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().toString();
    }
}

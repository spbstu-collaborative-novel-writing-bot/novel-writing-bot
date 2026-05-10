package ru.team.novelbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.domain.UserRole;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AppUser upsert(long chatId, String username, String displayName, UserRole role) {
        Optional<AppUser> existing = findByChatId(chatId);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    "UPDATE app_users SET username = ?, display_name = ?, role = ? WHERE chat_id = ?",
                    username,
                    displayName,
                    role.name(),
                    chatId
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO app_users(chat_id, username, display_name, role) VALUES (?, ?, ?, ?)",
                    chatId,
                    username,
                    displayName,
                    role.name()
            );
        }
        return findByChatId(chatId).orElseThrow();
    }

    public Optional<AppUser> findByChatId(long chatId) {
        List<AppUser> users = jdbcTemplate.query(
                "SELECT chat_id, username, display_name, role, created_at FROM app_users WHERE chat_id = ?",
                rowMapper(),
                chatId
        );
        return users.stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbcTemplate.query(
                "SELECT chat_id, username, display_name, role, created_at FROM app_users ORDER BY created_at, chat_id",
                rowMapper()
        );
    }

    private RowMapper<AppUser> rowMapper() {
        return (rs, rowNum) -> new AppUser(
                rs.getLong("chat_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                UserRole.fromDb(rs.getString("role")),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}

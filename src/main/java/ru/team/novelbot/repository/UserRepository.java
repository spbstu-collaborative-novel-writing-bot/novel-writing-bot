package ru.team.novelbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.domain.UserRole;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AppUser upsert(long chatId, String username, String displayName, UserRole role) {
        if (username != null) {
            jdbcTemplate.update(
                    "UPDATE app_users SET username = NULL WHERE chat_id <> ? AND LOWER(username) = LOWER(?)",
                    chatId,
                    username
            );
        }
        Optional<AppUser> existing = findByChatId(chatId);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    "UPDATE app_users SET username = ?, display_name = ? WHERE chat_id = ?",
                    username,
                    displayName,
                    chatId
            );
            if (role == UserRole.ADMIN && existing.get().role() != UserRole.ADMIN) {
                setRole(chatId, role);
            }
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

    public boolean setRole(long chatId, UserRole role) {
        return jdbcTemplate.update(
                "UPDATE app_users SET role = ? WHERE chat_id = ?",
                role.name(),
                chatId
        ) == 1;
    }

    public Optional<AppUser> findByChatId(long chatId) {
        List<AppUser> users = jdbcTemplate.query(
                "SELECT chat_id, username, display_name, role, created_at FROM app_users WHERE chat_id = ?",
                rowMapper(),
                chatId
        );
        return users.stream().findFirst();
    }

    public Optional<AppUser> findByUsername(String username) {
        List<AppUser> users = jdbcTemplate.query(
                """
                SELECT chat_id, username, display_name, role, created_at
                FROM app_users
                WHERE username IS NOT NULL AND LOWER(username) = LOWER(?)
                ORDER BY created_at DESC, chat_id DESC
                """,
                rowMapper(),
                username
        );
        return users.stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbcTemplate.query(
                "SELECT chat_id, username, display_name, role, created_at FROM app_users ORDER BY created_at, chat_id",
                rowMapper()
        );
    }

    public Map<Long, AppUser> findByChatIds(Set<Long> chatIds) {
        if (chatIds == null || chatIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = chatIds.stream()
                .map(chatId -> "?")
                .collect(Collectors.joining(", "));
        Object[] args = chatIds.toArray();
        return jdbcTemplate.query(
                        "SELECT chat_id, username, display_name, role, created_at FROM app_users WHERE chat_id IN (" + placeholders + ")",
                        rowMapper(),
                        args
                )
                .stream()
                .collect(Collectors.toMap(AppUser::chatId, Function.identity()));
    }

    private RowMapper<AppUser> rowMapper() {
        return (rs, rowNum) -> new AppUser(
                rs.getLong("chat_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                UserRole.fromDatabase(rs.getString("role")),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}

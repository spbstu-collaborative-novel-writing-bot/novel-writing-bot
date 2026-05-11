package ru.team.novelbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.team.novelbot.domain.AppUser;

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

    public AppUser upsert(long chatId, String username, String displayName) {
        Optional<AppUser> existing = findByChatId(chatId);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    "UPDATE app_users SET username = ?, display_name = ? WHERE chat_id = ?",
                    username,
                    displayName,
                    chatId
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO app_users(chat_id, username, display_name) VALUES (?, ?, ?)",
                    chatId,
                    username,
                    displayName
            );
        }
        return findByChatId(chatId).orElseThrow();
    }

    public Optional<AppUser> findByChatId(long chatId) {
        List<AppUser> users = jdbcTemplate.query(
                "SELECT chat_id, username, display_name, created_at FROM app_users WHERE chat_id = ?",
                rowMapper(),
                chatId
        );
        return users.stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbcTemplate.query(
                "SELECT chat_id, username, display_name, created_at FROM app_users ORDER BY created_at, chat_id",
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
                        "SELECT chat_id, username, display_name, created_at FROM app_users WHERE chat_id IN (" + placeholders + ")",
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
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}

package ru.team.novelbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.team.novelbot.domain.TelegramSession;

import java.sql.Types;
import java.util.Optional;

@Repository
public class TelegramSessionRepository {
    private final JdbcTemplate jdbcTemplate;

    public TelegramSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TelegramSession> findByChatId(long chatId) {
        return jdbcTemplate.query(
                """
                SELECT chat_id, state, novel_id, chapter_id, payload, updated_at
                FROM telegram_sessions
                WHERE chat_id = ?
                """,
                mapper(),
                chatId
        ).stream().findFirst();
    }

    public void save(long chatId, String state, Long novelId, Long chapterId, String payload) {
        delete(chatId);
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    """
                    INSERT INTO telegram_sessions(chat_id, state, novel_id, chapter_id, payload, updated_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """
            );
            ps.setLong(1, chatId);
            ps.setString(2, state);
            if (novelId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, novelId);
            }
            if (chapterId == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, chapterId);
            }
            ps.setString(5, payload == null ? "{}" : payload);
            return ps;
        });
    }

    public void delete(long chatId) {
        jdbcTemplate.update("DELETE FROM telegram_sessions WHERE chat_id = ?", chatId);
    }

    private RowMapper<TelegramSession> mapper() {
        return (rs, rowNum) -> {
            long novelValue = rs.getLong("novel_id");
            Long novelId = rs.wasNull() ? null : novelValue;
            long chapterValue = rs.getLong("chapter_id");
            Long chapterId = rs.wasNull() ? null : chapterValue;
            return new TelegramSession(
                    rs.getLong("chat_id"),
                    rs.getString("state"),
                    novelId,
                    chapterId,
                    rs.getString("payload"),
                    rs.getTimestamp("updated_at").toLocalDateTime()
            );
        };
    }
}

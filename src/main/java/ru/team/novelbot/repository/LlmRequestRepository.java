package ru.team.novelbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import ru.team.novelbot.domain.LlmRequest;
import ru.team.novelbot.domain.LlmRequestStatus;
import ru.team.novelbot.domain.LlmRequestType;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class LlmRequestRepository {
    private final JdbcTemplate jdbcTemplate;

    public LlmRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public LlmRequest create(
            long chatId,
            long novelId,
            Long chapterId,
            LlmRequestType type,
            String prompt,
            String provider,
            String model
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO llm_requests(chat_id, novel_id, chapter_id, request_type, status, prompt, provider, model)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    new String[]{"id"}
            );
            ps.setLong(1, chatId);
            ps.setLong(2, novelId);
            if (chapterId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, chapterId);
            }
            ps.setString(4, type.name());
            ps.setString(5, LlmRequestStatus.QUEUED.name());
            ps.setString(6, prompt);
            ps.setString(7, provider);
            ps.setString(8, model);
            return ps;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public Optional<LlmRequest> findById(long id) {
        return jdbcTemplate.query(
                """
                SELECT id, chat_id, novel_id, chapter_id, request_type, status, prompt, result,
                       error_message, created_at, updated_at, provider, model, completed_at
                FROM llm_requests
                WHERE id = ?
                """,
                rowMapper(),
                id
        ).stream().findFirst();
    }

    public void updateStatus(long id, LlmRequestStatus status, String result, String errorMessage) {
        jdbcTemplate.update(
                """
                UPDATE llm_requests
                SET status = ?, result = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP,
                    completed_at = ?
                WHERE id = ?
                """,
                status.name(),
                result,
                errorMessage,
                status == LlmRequestStatus.DONE || status == LlmRequestStatus.ERROR
                        ? java.sql.Timestamp.valueOf(LocalDateTime.now())
                        : null,
                id
        );
    }

    private RowMapper<LlmRequest> rowMapper() {
        return (rs, rowNum) -> {
            long chapterValue = rs.getLong("chapter_id");
            Long chapterId = rs.wasNull() ? null : chapterValue;
            return new LlmRequest(
                    rs.getLong("id"),
                    rs.getLong("chat_id"),
                    rs.getLong("novel_id"),
                    chapterId,
                    LlmRequestType.valueOf(rs.getString("request_type")),
                    LlmRequestStatus.valueOf(rs.getString("status")),
                    rs.getString("prompt"),
                    rs.getString("result"),
                    rs.getString("error_message"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime(),
                    rs.getString("provider"),
                    rs.getString("model"),
                    rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime()
            );
        };
    }
}

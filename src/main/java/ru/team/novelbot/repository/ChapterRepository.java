package ru.team.novelbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.ChapterHistory;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class ChapterRepository {
    private final JdbcTemplate jdbcTemplate;

    public ChapterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long novelId, String title, String text, long editorChatId) {
        int orderNumber = nextOrderNumber(novelId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO chapters(novel_id, title, text, order_number, last_editor_chat_id)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    new String[]{"id"}
            );
            ps.setLong(1, novelId);
            ps.setString(2, title);
            ps.setString(3, text);
            ps.setInt(4, orderNumber);
            ps.setLong(5, editorChatId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Chapter> findByNovelId(long novelId) {
        return jdbcTemplate.query(
                """
                SELECT id, novel_id, title, text, order_number, created_at, updated_at, last_editor_chat_id
                FROM chapters
                WHERE novel_id = ?
                ORDER BY order_number, id
                """,
                chapterMapper(),
                novelId
        );
    }

    public Optional<Chapter> findByIdAndNovelId(long chapterId, long novelId) {
        return jdbcTemplate.query(
                """
                SELECT id, novel_id, title, text, order_number, created_at, updated_at, last_editor_chat_id
                FROM chapters
                WHERE id = ? AND novel_id = ?
                """,
                chapterMapper(),
                chapterId,
                novelId
        ).stream().findFirst();
    }

    public void saveHistory(Chapter oldChapter, long editorChatId) {
        jdbcTemplate.update(
                """
                INSERT INTO chapter_history(chapter_id, old_title, old_text, editor_chat_id)
                VALUES (?, ?, ?, ?)
                """,
                oldChapter.id(),
                oldChapter.title(),
                oldChapter.text(),
                editorChatId
        );
        trimHistory(oldChapter.id());
    }

    public void update(long chapterId, String title, String text, long editorChatId) {
        jdbcTemplate.update(
                """
                UPDATE chapters
                SET title = ?, text = ?, last_editor_chat_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                title,
                text,
                editorChatId,
                chapterId
        );
    }

    public void delete(long chapterId, long novelId) {
        jdbcTemplate.update("DELETE FROM chapters WHERE id = ? AND novel_id = ?", chapterId, novelId);
    }

    public List<ChapterHistory> findHistory(long chapterId) {
        return jdbcTemplate.query(
                """
                SELECT id, chapter_id, old_title, old_text, editor_chat_id, changed_at
                FROM chapter_history
                WHERE chapter_id = ?
                ORDER BY changed_at DESC, id DESC
                LIMIT 5
                """,
                historyMapper(),
                chapterId
        );
    }

    private int nextOrderNumber(long novelId) {
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(order_number), 0) + 1 FROM chapters WHERE novel_id = ?",
                Integer.class,
                novelId
        );
        return next == null ? 1 : next;
    }

    private void trimHistory(long chapterId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM chapter_history WHERE chapter_id = ? ORDER BY changed_at DESC, id DESC",
                (rs, rowNum) -> rs.getLong("id"),
                chapterId
        );
        for (int i = 5; i < ids.size(); i++) {
            jdbcTemplate.update("DELETE FROM chapter_history WHERE id = ?", ids.get(i));
        }
    }

    private RowMapper<Chapter> chapterMapper() {
        return (rs, rowNum) -> new Chapter(
                rs.getLong("id"),
                rs.getLong("novel_id"),
                rs.getString("title"),
                rs.getString("text"),
                rs.getInt("order_number"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getLong("last_editor_chat_id")
        );
    }

    private RowMapper<ChapterHistory> historyMapper() {
        return (rs, rowNum) -> new ChapterHistory(
                rs.getLong("id"),
                rs.getLong("chapter_id"),
                rs.getString("old_title"),
                rs.getString("old_text"),
                rs.getLong("editor_chat_id"),
                rs.getTimestamp("changed_at").toLocalDateTime()
        );
    }
}

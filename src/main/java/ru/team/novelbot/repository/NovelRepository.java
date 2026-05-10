package ru.team.novelbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import ru.team.novelbot.domain.AuthorType;
import ru.team.novelbot.domain.Novel;
import ru.team.novelbot.domain.NovelAuthor;
import ru.team.novelbot.domain.NovelSummary;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class NovelRepository {
    private final JdbcTemplate jdbcTemplate;

    public NovelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(String title, String description, String genre, long ownerChatId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO novels(title, description, genre, owner_chat_id) VALUES (?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setString(1, title);
            ps.setString(2, description);
            ps.setString(3, genre);
            ps.setLong(4, ownerChatId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Novel> findById(long novelId) {
        return jdbcTemplate.query(
                """
                SELECT id, title, description, genre, owner_chat_id, created_at, updated_at
                FROM novels
                WHERE id = ?
                """,
                novelMapper(),
                novelId
        ).stream().findFirst();
    }

    public List<NovelSummary> findAccessibleByChatId(long chatId) {
        return jdbcTemplate.query(
                """
                SELECT n.id, n.title, n.genre, a.author_type
                FROM novels n
                JOIN novel_authors a ON a.novel_id = n.id
                WHERE a.chat_id = ?
                ORDER BY n.updated_at DESC, n.id DESC
                """,
                (rs, rowNum) -> new NovelSummary(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("genre"),
                        AuthorType.valueOf(rs.getString("author_type"))
                ),
                chatId
        );
    }

    public void deleteByOwner(long novelId, long ownerChatId) {
        jdbcTemplate.update("DELETE FROM novels WHERE id = ? AND owner_chat_id = ?", novelId, ownerChatId);
    }

    public boolean exists(long novelId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novels WHERE id = ?",
                Integer.class,
                novelId
        );
        return count != null && count > 0;
    }

    public void touch(long novelId) {
        jdbcTemplate.update("UPDATE novels SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", novelId);
    }

    public void addAuthor(long novelId, long chatId, AuthorType authorType) {
        jdbcTemplate.update(
                "INSERT INTO novel_authors(novel_id, chat_id, author_type) VALUES (?, ?, ?)",
                novelId,
                chatId,
                authorType.name()
        );
    }

    public boolean hasAccess(long novelId, long chatId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_authors WHERE novel_id = ? AND chat_id = ?",
                Integer.class,
                novelId,
                chatId
        );
        return count != null && count > 0;
    }

    public boolean isOwner(long novelId, long chatId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM novel_authors
                WHERE novel_id = ? AND chat_id = ? AND author_type = ?
                """,
                Integer.class,
                novelId,
                chatId,
                AuthorType.OWNER.name()
        );
        return count != null && count > 0;
    }

    public Optional<AuthorType> findAuthorType(long novelId, long chatId) {
        return jdbcTemplate.query(
                "SELECT author_type FROM novel_authors WHERE novel_id = ? AND chat_id = ?",
                (rs, rowNum) -> AuthorType.valueOf(rs.getString("author_type")),
                novelId,
                chatId
        ).stream().findFirst();
    }

    public List<NovelAuthor> findAuthors(long novelId) {
        return jdbcTemplate.query(
                """
                SELECT a.novel_id, a.chat_id, u.username, a.author_type
                FROM novel_authors a
                JOIN app_users u ON u.chat_id = a.chat_id
                WHERE a.novel_id = ?
                ORDER BY CASE WHEN a.author_type = 'OWNER' THEN 0 ELSE 1 END, a.chat_id
                """,
                (rs, rowNum) -> new NovelAuthor(
                        rs.getLong("novel_id"),
                        rs.getLong("chat_id"),
                        rs.getString("username"),
                        AuthorType.valueOf(rs.getString("author_type"))
                ),
                novelId
        );
    }

    public void removeCoAuthor(long novelId, long chatId) {
        jdbcTemplate.update(
                "DELETE FROM novel_authors WHERE novel_id = ? AND chat_id = ? AND author_type = ?",
                novelId,
                chatId,
                AuthorType.CO_AUTHOR.name()
        );
    }

    public int countChapters(long novelId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chapters WHERE novel_id = ?",
                Integer.class,
                novelId
        );
        return count == null ? 0 : count;
    }

    private RowMapper<Novel> novelMapper() {
        return (rs, rowNum) -> new Novel(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("genre"),
                rs.getLong("owner_chat_id"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}

package ru.team.novelbot.domain;

import java.time.LocalDateTime;

public record NovelSummary(
        long id,
        String title,
        String genre,
        AuthorType authorType,
        int chapterCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

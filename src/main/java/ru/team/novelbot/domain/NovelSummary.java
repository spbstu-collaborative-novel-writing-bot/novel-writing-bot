package ru.team.novelbot.domain;

public record NovelSummary(
        long id,
        String title,
        String genre,
        AuthorType authorType
) {
}

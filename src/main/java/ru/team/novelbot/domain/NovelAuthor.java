package ru.team.novelbot.domain;

public record NovelAuthor(
        long novelId,
        long chatId,
        String username,
        AuthorType authorType
) {
}

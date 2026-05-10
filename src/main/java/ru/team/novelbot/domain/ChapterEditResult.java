package ru.team.novelbot.domain;

public record ChapterEditResult(
        boolean saved,
        Chapter chapter
) {
}

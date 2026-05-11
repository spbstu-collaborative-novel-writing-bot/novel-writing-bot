package ru.team.novelbot.domain;

import java.time.LocalDateTime;

public record ChapterVersion(
        int versionNumber,
        long chapterId,
        String title,
        String text,
        LocalDateTime changedAt,
        long editorChatId,
        String editorName,
        boolean current
) {
}

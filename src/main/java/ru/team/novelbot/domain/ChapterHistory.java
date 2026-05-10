package ru.team.novelbot.domain;

import java.time.LocalDateTime;

public record ChapterHistory(
        long id,
        long chapterId,
        String oldTitle,
        String oldText,
        long editorChatId,
        LocalDateTime changedAt
) {
}

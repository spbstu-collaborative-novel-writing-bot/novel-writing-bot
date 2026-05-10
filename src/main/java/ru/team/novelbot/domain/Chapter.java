package ru.team.novelbot.domain;

import java.time.LocalDateTime;

public record Chapter(
        long id,
        long novelId,
        String title,
        String text,
        int orderNumber,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long lastEditorChatId
) {
}

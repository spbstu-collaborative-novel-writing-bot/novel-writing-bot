package ru.team.novelbot.domain;

import java.time.LocalDateTime;

public record TelegramSession(
        long chatId,
        String state,
        Long novelId,
        Long chapterId,
        String payload,
        LocalDateTime updatedAt
) {
}

package ru.team.novelbot.domain;

import java.time.LocalDateTime;

public record Novel(
        long id,
        String title,
        String description,
        String genre,
        long ownerChatId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

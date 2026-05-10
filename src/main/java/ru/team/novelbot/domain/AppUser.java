package ru.team.novelbot.domain;

import java.time.LocalDateTime;

public record AppUser(
        long chatId,
        String username,
        String displayName,
        UserRole role,
        LocalDateTime createdAt
) {
}

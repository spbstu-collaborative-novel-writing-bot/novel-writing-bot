package ru.team.novelbot.domain;

import java.time.LocalDateTime;

public record LlmRequest(
        long id,
        long chatId,
        long novelId,
        Long chapterId,
        LlmRequestType requestType,
        LlmRequestStatus status,
        String prompt,
        String result,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

package ru.team.novelbot.telegram;

public record TelegramInboundMessage(
        long chatId,
        String username,
        String displayName,
        String text,
        boolean textMessage
) {
}

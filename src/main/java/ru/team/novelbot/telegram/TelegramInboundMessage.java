package ru.team.novelbot.telegram;

public record TelegramInboundMessage(
        TelegramUpdateType type,
        long chatId,
        String username,
        String displayName,
        String text,
        boolean textMessage,
        String callbackQueryId,
        Integer callbackMessageId,
        String callbackData,
        String documentFileId,
        String documentFileName,
        String webAppData
) {
    public static TelegramInboundMessage text(long chatId, String username, String displayName, String text) {
        return new TelegramInboundMessage(
                TelegramUpdateType.MESSAGE,
                chatId,
                username,
                displayName,
                text,
                true,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}

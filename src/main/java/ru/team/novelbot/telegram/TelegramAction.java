package ru.team.novelbot.telegram;

import java.util.List;

public record TelegramAction(
        TelegramActionType type,
        long chatId,
        Integer messageId,
        String text,
        String filename,
        String callbackQueryId,
        boolean showAlert,
        List<List<TelegramButton>> keyboard
) {
    public static TelegramAction send(long chatId, String text, List<List<TelegramButton>> keyboard) {
        return new TelegramAction(TelegramActionType.SEND_MESSAGE, chatId, null, text, null, null, false, keyboard);
    }

    public static TelegramAction edit(long chatId, int messageId, String text, List<List<TelegramButton>> keyboard) {
        return new TelegramAction(TelegramActionType.EDIT_MESSAGE, chatId, messageId, text, null, null, false, keyboard);
    }

    public static TelegramAction document(long chatId, String filename, String text) {
        return new TelegramAction(TelegramActionType.SEND_DOCUMENT, chatId, null, text, filename, null, false, List.of());
    }

    public static TelegramAction answerCallback(String callbackQueryId, String text, boolean showAlert) {
        return new TelegramAction(TelegramActionType.ANSWER_CALLBACK, 0, null, text, null, callbackQueryId, showAlert, List.of());
    }
}

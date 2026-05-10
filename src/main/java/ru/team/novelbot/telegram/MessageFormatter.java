package ru.team.novelbot.telegram;

import ru.team.novelbot.service.TextTools;

public final class MessageFormatter {
    private static final int TELEGRAM_SAFE_LIMIT = 3900;

    private MessageFormatter() {
    }

    public static String telegramSafe(String text) {
        if (text == null || text.length() <= TELEGRAM_SAFE_LIMIT) {
            return text;
        }
        return text.substring(0, TELEGRAM_SAFE_LIMIT - 80)
                + "\n\nТекст был сокращён, потому что он слишком длинный для одного сообщения Telegram.";
    }

    public static String excerpt(String text, int maxLength) {
        return TextTools.compact(text, maxLength);
    }

    public static String username(String username) {
        return username == null || username.isBlank() ? "-" : "@" + username;
    }
}

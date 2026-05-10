package ru.team.novelbot.telegram;

public record TelegramButton(
        String text,
        String callbackData,
        String webAppUrl
) {
    public static TelegramButton callback(String text, String callbackData) {
        return new TelegramButton(text, callbackData, null);
    }

    public static TelegramButton webApp(String text, String webAppUrl) {
        return new TelegramButton(text, null, webAppUrl);
    }
}

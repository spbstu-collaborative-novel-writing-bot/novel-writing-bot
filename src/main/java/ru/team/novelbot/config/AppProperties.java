package ru.team.novelbot.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record AppProperties(
        String telegramBotToken,
        Set<Long> adminChatIds,
        String httpAdminToken,
        int httpPort,
        Database database,
        Rabbit rabbit,
        Llm llm,
        List<String> projectAuthors
) {
    public static AppProperties fromEnv(Map<String, String> env, boolean strict) {
        var missing = new StringBuilder();
        String telegramToken = value(env, "TELEGRAM_BOT_TOKEN", strict, missing);
        String adminIds = value(env, "ADMIN_CHAT_IDS", strict, missing);
        String httpToken = value(env, "HTTP_ADMIN_TOKEN", strict, missing);
        String pgHost = value(env, "POSTGRES_HOST", strict, missing);
        String pgPort = value(env, "POSTGRES_PORT", strict, missing);
        String pgDb = value(env, "POSTGRES_DB", strict, missing);
        String pgUser = value(env, "POSTGRES_USER", strict, missing);
        String pgPassword = value(env, "POSTGRES_PASSWORD", strict, missing);
        String rabbitHost = value(env, "RABBITMQ_HOST", strict, missing);
        String rabbitPort = value(env, "RABBITMQ_PORT", strict, missing);
        String rabbitUser = value(env, "RABBITMQ_USER", strict, missing);
        String rabbitPassword = value(env, "RABBITMQ_PASSWORD", strict, missing);

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Не заданы обязательные переменные окружения: " + missing);
        }

        Set<Long> parsedAdminIds = parseLongSet(adminIds);
        if (strict && parsedAdminIds.isEmpty()) {
            throw new IllegalStateException("ADMIN_CHAT_IDS должен содержать хотя бы один chat_id администратора.");
        }

        return new AppProperties(
                telegramToken,
                parsedAdminIds,
                httpToken,
                integer(env.getOrDefault("HTTP_PORT", "8080"), "HTTP_PORT"),
                new Database(pgHost, integer(pgPort, "POSTGRES_PORT"), pgDb, pgUser, pgPassword),
                new Rabbit(
                        rabbitHost,
                        integer(rabbitPort, "RABBITMQ_PORT"),
                        rabbitUser,
                        rabbitPassword,
                        env.getOrDefault("RABBITMQ_QUEUE", "llm.requests")
                ),
                new Llm(
                        env.getOrDefault("LLM_API_KEY", ""),
                        env.getOrDefault("LLM_API_URL", "https://api.openai.com/v1/chat/completions"),
                        env.getOrDefault("LLM_MODEL", "gpt-4o-mini")
                ),
                parseAuthors(env.getOrDefault(
                        "PROJECT_AUTHORS",
                        "Гвоздева Е.,Крутиков Д.,Михайлова А.,Романова А."
                ))
        );
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + database.host() + ":" + database.port() + "/" + database.name();
    }

    private static String value(Map<String, String> env, String key, boolean strict, StringBuilder missing) {
        String value = env.getOrDefault(key, "").trim();
        if (strict && value.isBlank()) {
            if (!missing.isEmpty()) {
                missing.append(", ");
            }
            missing.append(key);
        }
        return value;
    }

    private static int integer(String value, String key) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Переменная " + key + " должна быть числом.", ex);
        }
    }

    private static Set<Long> parseLongSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> parseAuthors(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    public record Database(String host, int port, String name, String user, String password) {
    }

    public record Rabbit(String host, int port, String username, String password, String queue) {
    }

    public record Llm(String apiKey, String apiUrl, String model) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}

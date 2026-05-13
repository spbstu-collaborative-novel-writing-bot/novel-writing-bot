package ru.team.novelbot.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public record AppProperties(
        String telegramBotToken,
        String telegramWebAppUrl,
        String httpAdminToken,
        int httpPort,
        Database database,
        Rabbit rabbit,
        Llm llm,
        List<Long> telegramAdminChatIds,
        List<String> projectAuthors
) {
    public static AppProperties fromEnv(Map<String, String> env, boolean strict) {
        var missing = new StringBuilder();
        String telegramToken = value(env, "TELEGRAM_BOT_TOKEN", strict, missing);
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

        String configuredProvider = env.getOrDefault("LLM_PROVIDER", "").trim();
        String llmApiKey = env.getOrDefault("LLM_API_KEY", "");
        String gigachatAuthKey = env.getOrDefault("GIGACHAT_AUTH_KEY", "");
        String provider = configuredProvider.isBlank()
                ? (!llmApiKey.isBlank() && gigachatAuthKey.isBlank() ? Llm.OPENAI_COMPATIBLE : Llm.GIGACHAT)
                : configuredProvider;

        return new AppProperties(
                telegramToken,
                env.getOrDefault("TELEGRAM_WEB_APP_URL", "").trim(),
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
                        provider,
                        llmApiKey,
                        env.getOrDefault("LLM_API_URL", ""),
                        env.getOrDefault("LLM_MODEL", ""),
                        gigachatAuthKey,
                        env.getOrDefault("GIGACHAT_SCOPE", "GIGACHAT_API_PERS"),
                        env.getOrDefault("GIGACHAT_OAUTH_URL", "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"),
                        env.getOrDefault("GIGACHAT_CA_CERT_PATH", "").trim(),
                        bool(env.getOrDefault("GIGACHAT_VERIFY_SSL", "true"), "GIGACHAT_VERIFY_SSL")
                ),
                parseLongList(env.getOrDefault("TELEGRAM_ADMIN_CHAT_IDS", "")),
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

    private static boolean bool(String value, String key) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        throw new IllegalStateException("Переменная " + key + " должна быть true или false.");
    }

    private static List<String> parseAuthors(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static List<Long> parseLongList(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> {
                    try {
                        return Long.parseLong(item);
                    } catch (NumberFormatException ex) {
                        throw new IllegalStateException("TELEGRAM_ADMIN_CHAT_IDS must contain comma-separated Telegram chat_id values.", ex);
                    }
                })
                .distinct()
                .toList();
    }

    public record Database(String host, int port, String name, String user, String password) {
    }

    public record Rabbit(String host, int port, String username, String password, String queue) {
    }

    public record Llm(
            String provider,
            String apiKey,
            String apiUrl,
            String model,
            String gigachatAuthKey,
            String gigachatScope,
            String gigachatOauthUrl,
            String gigachatCaCertPath,
            boolean gigachatVerifySsl
    ) {
        public static final String GIGACHAT = "GIGACHAT";
        public static final String OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";

        public boolean gigachat() {
            return GIGACHAT.equalsIgnoreCase(provider);
        }

        public boolean enabled() {
            if (gigachat()) {
                return gigachatAuthKey != null && !gigachatAuthKey.isBlank();
            }
            return apiKey != null && !apiKey.isBlank();
        }

        public String effectiveApiUrl() {
            if (gigachat()) {
                return apiUrl == null || apiUrl.isBlank()
                        ? "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
                        : apiUrl;
            }
            return apiUrl == null || apiUrl.isBlank()
                    ? "https://api.openai.com/v1/chat/completions"
                    : apiUrl;
        }

        public String effectiveModel() {
            if (model != null && !model.isBlank()) {
                return model;
            }
            return gigachat() ? "GigaChat-2" : "gpt-4o-mini";
        }

        public String effectiveProvider() {
            return provider == null || provider.isBlank() ? GIGACHAT : provider;
        }
    }
}

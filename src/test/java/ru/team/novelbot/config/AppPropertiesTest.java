package ru.team.novelbot.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {
    @Test
    void strictConfigDoesNotRequireTelegramAdminChatIds() {
        AppProperties properties = AppProperties.fromEnv(Map.ofEntries(
                Map.entry("TELEGRAM_BOT_TOKEN", "telegram-token"),
                Map.entry("HTTP_ADMIN_TOKEN", "admin-token"),
                Map.entry("POSTGRES_HOST", "postgres"),
                Map.entry("POSTGRES_PORT", "5432"),
                Map.entry("POSTGRES_DB", "novelbot"),
                Map.entry("POSTGRES_USER", "novelbot"),
                Map.entry("POSTGRES_PASSWORD", "password"),
                Map.entry("RABBITMQ_HOST", "rabbitmq"),
                Map.entry("RABBITMQ_PORT", "5672"),
                Map.entry("RABBITMQ_USER", "novelbot"),
                Map.entry("RABBITMQ_PASSWORD", "password"),
                Map.entry("GIGACHAT_AUTH_KEY", "auth-key")
        ), true);

        assertThat(properties.httpAdminToken()).isEqualTo("admin-token");
        assertThat(properties.llm().gigachatVerifySsl()).isTrue();
    }
}

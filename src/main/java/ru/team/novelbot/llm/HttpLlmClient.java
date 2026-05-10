package ru.team.novelbot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class HttpLlmClient implements LlmClient {
    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile CachedToken gigachatToken;

    public HttpLlmClient(AppProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String generate(String prompt) {
        if (!properties.llm().enabled()) {
            throw new IllegalStateException("LLM не настроена: задайте ключ провайдера.");
        }
        try {
            String token = properties.llm().gigachat() ? gigachatAccessToken() : properties.llm().apiKey();
            Map<String, Object> payload = Map.of(
                    "model", properties.llm().effectiveModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", "Ты помогаешь авторам писать художественные тексты на русском языке."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.7
            );
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.llm().effectiveApiUrl()))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("LLM API вернул HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IllegalStateException("LLM API вернул пустой ответ.");
            }
            return content.trim();
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось получить ответ от языковой модели.", ex);
        }
    }

    private String gigachatAccessToken() {
        CachedToken cached = gigachatToken;
        if (cached != null && cached.valid()) {
            return cached.value();
        }
        synchronized (this) {
            cached = gigachatToken;
            if (cached != null && cached.valid()) {
                return cached.value();
            }
            gigachatToken = requestGigachatToken();
            return gigachatToken.value();
        }
    }

    private CachedToken requestGigachatToken() {
        try {
            String body = "scope=" + properties.llm().gigachatScope();
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.llm().gigachatOauthUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("RqUID", UUID.randomUUID().toString())
                    .header("Authorization", "Basic " + properties.llm().gigachatAuthKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("GigaChat OAuth вернул HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String token = root.path("access_token").asText("");
            long expiresAtRaw = root.path("expires_at").asLong(0);
            if (token.isBlank()) {
                throw new IllegalStateException("GigaChat OAuth вернул пустой access_token.");
            }
            Instant expiresAt = expiresAtRaw > 10_000_000_000L
                    ? Instant.ofEpochMilli(expiresAtRaw)
                    : Instant.ofEpochSecond(expiresAtRaw);
            return new CachedToken(token, expiresAt.minusSeconds(60));
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось получить access_token GigaChat.", ex);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean valid() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}

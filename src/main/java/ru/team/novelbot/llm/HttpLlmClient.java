package ru.team.novelbot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class HttpLlmClient implements LlmClient {
    private static final String DEFAULT_GIGACHAT_CHAT_URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions";
    private static final String DEFAULT_GIGACHAT_OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    private static final String DEFAULT_GIGACHAT_SCOPE = "GIGACHAT_API_PERS";
    private static final String DEFAULT_GIGACHAT_MODEL = "GigaChat-2";

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
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt пустой.");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", effectiveModel());
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", "Ты помогаешь авторам писать художественные тексты на русском языке."),
                    Map.of("role", "user", "content", prompt)
            ));
            payload.put("temperature", 0.7);

            if (properties.llm().gigachat()) {
                payload.put("stream", false);
                payload.put("max_tokens", 1024);
            }

            String json = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = sendChatRequest(json, accessToken());

            if (properties.llm().gigachat() && response.statusCode() == 401) {
                gigachatToken = null;
                response = sendChatRequest(json, gigachatAccessToken());
            }

            if (response.statusCode() >= 300) {
                throw new IllegalStateException(httpDiagnostic("LLM API", response));
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IllegalStateException("LLM API вернул пустой ответ: " + sanitize(response.body()));
            }
            return content.trim();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Не удалось получить ответ от языковой модели: поток был прерван.", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось получить ответ от языковой модели: " + rootCauseMessage(ex), ex);
        }
    }

    private String accessToken() {
        if (properties.llm().gigachat()) {
            return gigachatAccessToken();
        }
        String apiKey = trimToNull(properties.llm().apiKey());
        if (apiKey == null) {
            throw new IllegalStateException("LLM API key пустой.");
        }
        return apiKey;
    }

    private HttpResponse<String> sendChatRequest(String json, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(chatUrl()))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        String requestId = UUID.randomUUID().toString();
        try {
            String body = "scope=" + URLEncoder.encode(gigachatScope(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(gigachatOauthUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("RqUID", requestId)
                    .header("Authorization", gigachatAuthorizationHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(httpDiagnostic("GigaChat OAuth, RqUID=" + requestId, response));
            }

            JsonNode root = objectMapper.readTree(response.body());
            String token = root.path("access_token").asText("");
            long expiresAtRaw = root.path("expires_at").asLong(0);

            if (token.isBlank()) {
                throw new IllegalStateException("GigaChat OAuth вернул пустой access_token: " + sanitize(response.body()));
            }

            Instant expiresAt;
            if (expiresAtRaw <= 0) {
                expiresAt = Instant.now().plusSeconds(25 * 60);
            } else if (expiresAtRaw > 10_000_000_000L) {
                expiresAt = Instant.ofEpochMilli(expiresAtRaw);
            } else {
                expiresAt = Instant.ofEpochSecond(expiresAtRaw);
            }

            return new CachedToken(token, expiresAt.minusSeconds(60));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Не удалось получить access_token GigaChat: поток был прерван.", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось получить access_token GigaChat: " + rootCauseMessage(ex), ex);
        }
    }

    private String gigachatAuthorizationHeader() {
        String key = firstNonBlank(properties.llm().gigachatAuthKey(), properties.llm().apiKey());
        if (key == null) {
            throw new IllegalStateException("GigaChat authorization key пустой. Укажите gigachatAuthKey или apiKey.");
        }

        if (key.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new IllegalStateException("Для GigaChat OAuth нужен Authorization key в Basic-схеме, а не Bearer access_token.");
        }

        if (key.regionMatches(true, 0, "Basic ", 0, "Basic ".length())) {
            return "Basic " + key.substring("Basic ".length()).trim();
        }

        if (key.contains(":")) {
            key = Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8));
        }

        return "Basic " + key;
    }

    private String effectiveModel() {
        String model = trimToNull(properties.llm().effectiveModel());
        if (!properties.llm().gigachat()) {
            if (model == null) {
                throw new IllegalStateException("LLM model пустая.");
            }
            return model;
        }
        if (model == null) {
            return DEFAULT_GIGACHAT_MODEL;
        }
        return normalizeGigachatModel(model);
    }

    private String normalizeGigachatModel(String model) {
        return switch (model) {
            case "GigaChat", "GigaChat-Lite", "GigaChat Lite", "GigaChat-2-Lite" -> "GigaChat-2";
            case "GigaChat-Pro", "GigaChat Pro" -> "GigaChat-2-Pro";
            case "GigaChat-Max", "GigaChat Max" -> "GigaChat-2-Max";
            default -> model;
        };
    }

    private String chatUrl() {
        String url = trimToNull(properties.llm().effectiveApiUrl());
        if (url == null && properties.llm().gigachat()) {
            return DEFAULT_GIGACHAT_CHAT_URL;
        }
        if (url == null) {
            throw new IllegalStateException("LLM API URL пустой.");
        }
        return url;
    }

    private String gigachatOauthUrl() {
        String url = trimToNull(properties.llm().gigachatOauthUrl());
        return url == null ? DEFAULT_GIGACHAT_OAUTH_URL : url;
    }

    private String gigachatScope() {
        String scope = trimToNull(properties.llm().gigachatScope());
        return scope == null ? DEFAULT_GIGACHAT_SCOPE : scope;
    }

    private String httpDiagnostic(String service, HttpResponse<String> response) {
        String detail = errorDetail(response.body());
        return detail.isBlank()
                ? service + " вернул HTTP " + response.statusCode() + "."
                : service + " вернул HTTP " + response.statusCode() + ": " + detail;
    }

    private String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = firstText(root, "message", "error_description", "error", "detail");
            if (!message.isBlank()) {
                return sanitize(message);
            }
        } catch (Exception ignored) {
            // Fall back to compact raw body below.
        }
        return sanitize(body.length() <= 1000 ? body : body.substring(0, 1000) + "...");
    }

    private String firstText(JsonNode root, String... names) {
        for (String name : names) {
            String value = root.path(name).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return sanitize(message == null ? current.getClass().getSimpleName() : message);
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)(authorization|token|key|secret|password)=\\S+", "$1=***")
                .replaceAll("(?i)(Bearer|Basic)\\s+[^\\s,;]+", "$1 ***")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean valid() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}

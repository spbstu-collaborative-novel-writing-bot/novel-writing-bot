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
import java.util.List;
import java.util.Map;

@Component
public class HttpLlmClient implements LlmClient {
    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpLlmClient(AppProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String generate(String prompt) {
        if (!properties.llm().enabled()) {
            throw new IllegalStateException("LLM_API_KEY не задан.");
        }
        try {
            Map<String, Object> payload = Map.of(
                    "model", properties.llm().model(),
                    "messages", List.of(
                            Map.of("role", "system", "content", "Ты помогаешь авторам писать художественные тексты на русском языке."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.7
            );
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.llm().apiUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.llm().apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("LLM API вернул HTTP " + response.statusCode());
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
}

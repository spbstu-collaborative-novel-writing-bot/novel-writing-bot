package ru.team.novelbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class TelegramBotAdapter {
    private static final Logger log = LoggerFactory.getLogger(TelegramBotAdapter.class);

    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CommandRouter commandRouter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private long offset;

    public TelegramBotAdapter(
            AppProperties properties,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            CommandRouter commandRouter
    ) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.commandRouter = commandRouter;
    }

    public void start() {
        running = true;
        executor.submit(this::pollLoop);
        log.info("Telegram long polling started.");
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void pollLoop() {
        while (running) {
            try {
                pollOnce();
            } catch (Exception ex) {
                log.warn("Ошибка Telegram long polling: {}", ex.getMessage());
                sleep(3000);
            }
        }
    }

    private void pollOnce() throws IOException, InterruptedException {
        String url = telegramUrl("getUpdates")
                + "?timeout=30&offset=" + offset + "&allowed_updates="
                + encode("[\"message\"]");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = objectMapper.readTree(response.body());
        if (!root.path("ok").asBoolean(false)) {
            log.warn("Telegram getUpdates вернул ошибку: {}", response.body());
            sleep(3000);
            return;
        }
        for (JsonNode update : root.path("result")) {
            offset = update.path("update_id").asLong() + 1;
            TelegramInboundMessage inbound = toInbound(update);
            if (inbound != null) {
                String answer = commandRouter.route(inbound);
                sendMessage(inbound.chatId(), answer);
            }
        }
    }

    private TelegramInboundMessage toInbound(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) {
            return null;
        }
        long chatId = message.path("chat").path("id").asLong();
        JsonNode from = message.path("from");
        String username = nullableText(from.path("username"));
        String displayName = displayName(from);
        JsonNode text = message.path("text");
        return new TelegramInboundMessage(
                chatId,
                username,
                displayName,
                text.isMissingNode() ? null : text.asText(),
                !text.isMissingNode()
        );
    }

    private String displayName(JsonNode from) {
        String first = nullableText(from.path("first_name"));
        String last = nullableText(from.path("last_name"));
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return joined.isBlank() ? null : joined;
    }

    private String nullableText(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private void sendMessage(long chatId, String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    "text", MessageFormatter.telegramSafe(text),
                    "disable_web_page_preview", true
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(telegramUrl("sendMessage")))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                log.warn("Telegram sendMessage вернул HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            log.warn("Не удалось отправить сообщение в Telegram: {}", ex.getMessage());
        }
    }

    private String telegramUrl(String method) {
        return "https://api.telegram.org/bot" + properties.telegramBotToken() + "/" + method;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

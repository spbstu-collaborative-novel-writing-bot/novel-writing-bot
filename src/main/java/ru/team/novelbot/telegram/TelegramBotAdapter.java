package ru.team.novelbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class TelegramBotAdapter {
    private static final Logger log = LoggerFactory.getLogger(TelegramBotAdapter.class);

    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CommandRouter commandRouter;
    private final TelegramClient telegramClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private long offset;

    public TelegramBotAdapter(
            AppProperties properties,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            CommandRouter commandRouter,
            TelegramClient telegramClient
    ) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.commandRouter = commandRouter;
        this.telegramClient = telegramClient;
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
                + encode("[\"message\",\"callback_query\"]");
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
                TelegramResponse answer = commandRouter.route(inbound);
                for (TelegramAction action : answer.actions()) {
                    telegramClient.execute(action);
                }
            }
        }
    }

    private TelegramInboundMessage toInbound(JsonNode update) {
        if (update.has("callback_query")) {
            return callbackInbound(update.path("callback_query"));
        }
        JsonNode message = update.path("message");
        if (message.isMissingNode()) {
            return null;
        }
        return messageInbound(message);
    }

    private TelegramInboundMessage callbackInbound(JsonNode callback) {
        JsonNode message = callback.path("message");
        long chatId = message.path("chat").path("id").asLong();
        JsonNode from = callback.path("from");
        return new TelegramInboundMessage(
                TelegramUpdateType.CALLBACK,
                chatId,
                nullableText(from.path("username")),
                displayName(from),
                null,
                false,
                nullableText(callback.path("id")),
                message.path("message_id").isMissingNode() ? null : message.path("message_id").asInt(),
                nullableText(callback.path("data")),
                null,
                null,
                null
        );
    }

    private TelegramInboundMessage messageInbound(JsonNode message) {
        long chatId = message.path("chat").path("id").asLong();
        JsonNode from = message.path("from");
        String username = nullableText(from.path("username"));
        String displayName = displayName(from);
        JsonNode text = message.path("text");
        JsonNode document = message.path("document");
        JsonNode webAppData = message.path("web_app_data");
        return new TelegramInboundMessage(
                TelegramUpdateType.MESSAGE,
                chatId,
                username,
                displayName,
                text.isMissingNode() ? null : text.asText(),
                !text.isMissingNode(),
                null,
                null,
                null,
                document.isMissingNode() ? null : nullableText(document.path("file_id")),
                document.isMissingNode() ? null : nullableText(document.path("file_name")),
                webAppData.isMissingNode() ? null : nullableText(webAppData.path("data"))
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

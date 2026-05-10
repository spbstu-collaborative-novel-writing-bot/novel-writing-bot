package ru.team.novelbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TelegramClient {
    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TelegramClient(AppProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public void execute(TelegramAction action) {
        switch (action.type()) {
            case SEND_MESSAGE -> sendMessage(action.chatId(), action.text(), action.keyboard());
            case EDIT_MESSAGE -> editMessage(action.chatId(), action.messageId(), action.text(), action.keyboard());
            case SEND_DOCUMENT -> sendDocument(action.chatId(), action.filename(), action.text());
            case ANSWER_CALLBACK -> answerCallbackQuery(action.callbackQueryId(), action.text(), action.showAlert());
        }
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, List.of());
    }

    public void sendMessage(long chatId, String text, List<List<TelegramButton>> keyboard) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", MessageFormatter.telegramSafe(text));
            body.put("disable_web_page_preview", true);
            addKeyboard(body, keyboard);
            postJson("sendMessage", body);
        } catch (Exception ex) {
            log.warn("Не удалось отправить сообщение в Telegram: {}", ex.getMessage());
        }
    }

    public void editMessage(long chatId, Integer messageId, String text, List<List<TelegramButton>> keyboard) {
        if (messageId == null) {
            sendMessage(chatId, text, keyboard);
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("message_id", messageId);
            body.put("text", MessageFormatter.telegramSafe(text));
            body.put("disable_web_page_preview", true);
            addKeyboard(body, keyboard);
            postJson("editMessageText", body);
        } catch (Exception ex) {
            log.warn("Не удалось обновить сообщение Telegram: {}", ex.getMessage());
            sendMessage(chatId, text, keyboard);
        }
    }

    public void answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        if (callbackQueryId == null || callbackQueryId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("callback_query_id", callbackQueryId);
            if (text != null && !text.isBlank()) {
                body.put("text", MessageFormatter.telegramSafe(text));
            }
            body.put("show_alert", showAlert);
            postJson("answerCallbackQuery", body);
        } catch (Exception ex) {
            log.warn("Не удалось ответить на callback Telegram: {}", ex.getMessage());
        }
    }

    public void sendDocument(long chatId, String filename, String text) {
        try {
            String boundary = "----novelbot-" + UUID.randomUUID();
            byte[] body = multipart(boundary, chatId, filename, text);
            HttpRequest request = HttpRequest.newBuilder(URI.create(telegramUrl("sendDocument")))
                    .timeout(Duration.ofSeconds(40))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                log.warn("Telegram sendDocument вернул HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            log.warn("Не удалось отправить документ в Telegram: {}", ex.getMessage());
        }
    }

    public String downloadFileText(String fileId) {
        try {
            JsonNode root = postJson("getFile", Map.of("file_id", fileId));
            String filePath = root.path("result").path("file_path").asText("");
            if (filePath.isBlank()) {
                throw new IllegalStateException("Telegram не вернул file_path.");
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(fileUrl(filePath)))
                    .timeout(Duration.ofSeconds(40))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Telegram file API вернул HTTP " + response.statusCode());
            }
            return response.body();
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось скачать файл из Telegram.", ex);
        }
    }

    private JsonNode postJson(String method, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(telegramUrl(method)))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Telegram " + method + " вернул HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (!root.path("ok").asBoolean(false)) {
            throw new IllegalStateException("Telegram " + method + " вернул ошибку: " + response.body());
        }
        return root;
    }

    private void addKeyboard(Map<String, Object> body, List<List<TelegramButton>> keyboard) {
        if (keyboard == null || keyboard.isEmpty()) {
            return;
        }
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (List<TelegramButton> row : keyboard) {
            List<Map<String, Object>> buttons = new ArrayList<>();
            for (TelegramButton button : row) {
                Map<String, Object> buttonBody = new LinkedHashMap<>();
                buttonBody.put("text", button.text());
                if (button.webAppUrl() != null && !button.webAppUrl().isBlank()) {
                    buttonBody.put("web_app", Map.of("url", button.webAppUrl()));
                } else {
                    buttonBody.put("callback_data", button.callbackData());
                }
                buttons.add(buttonBody);
            }
            rows.add(buttons);
        }
        body.put("reply_markup", Map.of("inline_keyboard", rows));
    }

    private byte[] multipart(String boundary, long chatId, String filename, String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePart(out, boundary, "chat_id", Long.toString(chatId));
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"document\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void writePart(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String telegramUrl(String method) {
        return "https://api.telegram.org/bot" + properties.telegramBotToken() + "/" + method;
    }

    private String fileUrl(String filePath) {
        return "https://api.telegram.org/file/bot" + properties.telegramBotToken() + "/" + filePath;
    }
}

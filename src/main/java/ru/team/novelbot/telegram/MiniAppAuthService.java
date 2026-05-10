package ru.team.novelbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.service.AccessDeniedException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MiniAppAuthService {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public MiniAppAuthService(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public long requireChatId(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new AccessDeniedException("Не переданы данные Telegram Mini App.");
        }
        try {
            Map<String, String> values = parse(initData);
            String hash = values.remove("hash");
            if (hash == null || hash.isBlank()) {
                throw new AccessDeniedException("Не передана подпись Telegram Mini App.");
            }
            List<String> pairs = new ArrayList<>();
            values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> pairs.add(entry.getKey() + "=" + entry.getValue()));
            String dataCheckString = String.join("\n", pairs);
            byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), properties.telegramBotToken());
            String calculated = HexFormat.of().formatHex(hmac(secret, dataCheckString));
            if (!MessageDigest.isEqual(calculated.getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8))) {
                throw new AccessDeniedException("Подпись Telegram Mini App неверна.");
            }
            JsonNode user = objectMapper.readTree(values.getOrDefault("user", "{}"));
            long chatId = user.path("id").asLong(0);
            if (chatId == 0) {
                throw new AccessDeniedException("Telegram Mini App не передал пользователя.");
            }
            return chatId;
        } catch (AccessDeniedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AccessDeniedException("Не удалось проверить Telegram Mini App.");
        }
    }

    private Map<String, String> parse(String initData) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String item : initData.split("&")) {
            int index = item.indexOf('=');
            if (index < 0) {
                continue;
            }
            String key = URLDecoder.decode(item.substring(0, index), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(item.substring(index + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    private byte[] hmac(byte[] key, String value) throws Exception {
        return hmac(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }
}

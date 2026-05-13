package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.domain.UserRole;
import ru.team.novelbot.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class UserAuthService {
    private static final Pattern TELEGRAM_USERNAME = Pattern.compile("[A-Za-z0-9_]{5,32}");

    private final AppProperties properties;
    private final UserRepository userRepository;

    public UserAuthService(AppProperties properties, UserRepository userRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
    }

    public AppUser registerOrUpdate(long chatId, String username, String displayName) {
        return userRepository.upsert(chatId, emptyToNull(username), emptyToNull(displayName), roleFor(chatId));
    }

    public Optional<AppUser> findByChatId(long chatId) {
        return userRepository.findByChatId(chatId);
    }

    public AppUser requireUserByTelegramTag(String telegramTag) {
        String username = normalizeTelegramTag(telegramTag);
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException("Пользователь @" + username + " не найден. Он должен сначала запустить бота."));
    }

    public AppUser requireUser(long chatId) {
        return userRepository.findByChatId(chatId)
                .orElseThrow(() -> new AppException("Пользователь не найден. Сначала выполните /start."));
    }

    public List<AppUser> findAll() {
        return userRepository.findAll();
    }

    public AppUser updateRole(long chatId, UserRole role) {
        if (role == UserRole.USER && properties.telegramAdminChatIds().contains(chatId)) {
            throw new UsageException("Р­С‚РѕС‚ Р°РґРјРёРЅ Р·Р°РґР°РЅ РІ TELEGRAM_ADMIN_CHAT_IDS. РЈР±РµСЂРёС‚Рµ chat_id РёР· .env Рё РїРµСЂРµР·Р°РїСѓСЃС‚РёС‚Рµ РїСЂРёР»РѕР¶РµРЅРёРµ.");
        }
        if (!userRepository.setRole(chatId, role)) {
            throw new AppException("РџРѕР»СЊР·РѕРІР°С‚РµР»СЊ РЅРµ РЅР°Р№РґРµРЅ. РћРЅ РґРѕР»Р¶РµРЅ СЃРЅР°С‡Р°Р»Р° РІС‹РїРѕР»РЅРёС‚СЊ /start.");
        }
        return requireUser(chatId);
    }

    private UserRole roleFor(long chatId) {
        return properties.telegramAdminChatIds().contains(chatId) ? UserRole.ADMIN : UserRole.USER;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeTelegramTag(String value) {
        String username = value == null ? "" : value.trim();
        if (username.startsWith("@")) {
            username = username.substring(1);
        }
        if (!TELEGRAM_USERNAME.matcher(username).matches()) {
            throw new UsageException("Отправьте тег Telegram в формате @username.");
        }
        return username;
    }
}

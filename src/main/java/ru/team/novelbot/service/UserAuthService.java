package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class UserAuthService {
    private static final Pattern TELEGRAM_USERNAME = Pattern.compile("[A-Za-z0-9_]{5,32}");

    private final UserRepository userRepository;

    public UserAuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser registerOrUpdate(long chatId, String username, String displayName) {
        return userRepository.upsert(chatId, emptyToNull(username), emptyToNull(displayName));
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

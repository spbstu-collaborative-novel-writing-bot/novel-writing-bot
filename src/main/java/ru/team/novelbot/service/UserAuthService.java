package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import ru.team.novelbot.config.AppProperties;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.domain.UserRole;
import ru.team.novelbot.repository.UserRepository;

import java.util.List;
import java.util.Optional;

@Service
public class UserAuthService {
    private final UserRepository userRepository;
    private final AppProperties properties;

    public UserAuthService(UserRepository userRepository, AppProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    public AppUser registerOrUpdate(long chatId, String username, String displayName) {
        UserRole role = properties.adminChatIds().contains(chatId) ? UserRole.ADMIN : UserRole.USER;
        return userRepository.upsert(chatId, emptyToNull(username), emptyToNull(displayName), role);
    }

    public Optional<AppUser> findByChatId(long chatId) {
        return userRepository.findByChatId(chatId);
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
}

package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.repository.UserRepository;

import java.util.List;
import java.util.Optional;

@Service
public class UserAuthService {
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

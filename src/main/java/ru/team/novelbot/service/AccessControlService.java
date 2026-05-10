package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import ru.team.novelbot.repository.NovelRepository;

@Service
public class AccessControlService {
    private final NovelRepository novelRepository;

    public AccessControlService(NovelRepository novelRepository) {
        this.novelRepository = novelRepository;
    }

    public void requireNovelExists(long novelId) {
        if (!novelRepository.exists(novelId)) {
            throw new AppException("Произведение не найдено.");
        }
    }

    public void requireAccess(long novelId, long chatId) {
        requireNovelExists(novelId);
        if (!novelRepository.hasAccess(novelId, chatId)) {
            throw new AccessDeniedException("Нет доступа к этому произведению.");
        }
    }

    public void requireOwner(long novelId, long chatId) {
        requireNovelExists(novelId);
        if (!novelRepository.isOwner(novelId, chatId)) {
            throw new AccessDeniedException("Действие доступно только владельцу произведения.");
        }
    }
}

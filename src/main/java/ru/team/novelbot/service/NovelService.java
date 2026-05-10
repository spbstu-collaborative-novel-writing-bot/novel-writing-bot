package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.domain.AuthorType;
import ru.team.novelbot.domain.Novel;
import ru.team.novelbot.domain.NovelAuthor;
import ru.team.novelbot.domain.NovelDetails;
import ru.team.novelbot.domain.NovelStats;
import ru.team.novelbot.domain.NovelSummary;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.NovelRepository;

import java.util.List;

@Service
public class NovelService {
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final UserAuthService userAuthService;
    private final AccessControlService accessControlService;
    private final TransactionTemplate transactionTemplate;

    public NovelService(
            NovelRepository novelRepository,
            ChapterRepository chapterRepository,
            UserAuthService userAuthService,
            AccessControlService accessControlService,
            TransactionTemplate transactionTemplate
    ) {
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.userAuthService = userAuthService;
        this.accessControlService = accessControlService;
        this.transactionTemplate = transactionTemplate;
    }

    public Novel createNovel(long chatId, String title, String description, String genre) {
        userAuthService.requireUser(chatId);
        return transactionTemplate.execute(status -> {
            long id = novelRepository.insert(title, description, genre, chatId);
            novelRepository.addAuthor(id, chatId, AuthorType.OWNER);
            return novelRepository.findById(id).orElseThrow();
        });
    }

    public List<NovelSummary> listAccessible(long chatId) {
        userAuthService.requireUser(chatId);
        return novelRepository.findAccessibleByChatId(chatId);
    }

    public NovelDetails getDetails(long chatId, long novelId) {
        accessControlService.requireAccess(novelId, chatId);
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new AppException("Произведение не найдено."));
        AppUser owner = userAuthService.requireUser(novel.ownerChatId());
        List<NovelAuthor> authors = novelRepository.findAuthors(novelId);
        return new NovelDetails(novel, owner, authors, stats(novelId));
    }

    public NovelStats stats(long chatId, long novelId) {
        accessControlService.requireAccess(novelId, chatId);
        return stats(novelId);
    }

    public void deleteNovel(long chatId, long novelId) {
        accessControlService.requireOwner(novelId, chatId);
        novelRepository.deleteByOwner(novelId, chatId);
    }

    public void inviteAuthor(long ownerChatId, long novelId, long invitedChatId) {
        accessControlService.requireOwner(novelId, ownerChatId);
        userAuthService.findByChatId(invitedChatId)
                .orElseThrow(() -> new AppException("Пользователь с указанным chat_id не найден. Он должен сначала запустить бота."));
        if (novelRepository.findAuthorType(novelId, invitedChatId).isPresent()) {
            throw new AppException("Этот пользователь уже является автором произведения.");
        }
        novelRepository.addAuthor(novelId, invitedChatId, AuthorType.CO_AUTHOR);
    }

    public void removeAuthor(long ownerChatId, long novelId, long targetChatId) {
        accessControlService.requireOwner(novelId, ownerChatId);
        if (novelRepository.isOwner(novelId, targetChatId)) {
            throw new AppException("Владельца нельзя удалить из списка авторов.");
        }
        if (novelRepository.findAuthorType(novelId, targetChatId).isEmpty()) {
            throw new AppException("Указанный пользователь не является соавтором этого произведения.");
        }
        novelRepository.removeCoAuthor(novelId, targetChatId);
    }

    public List<NovelAuthor> listAuthors(long chatId, long novelId) {
        accessControlService.requireAccess(novelId, chatId);
        return novelRepository.findAuthors(novelId);
    }

    public Novel requireNovel(long chatId, long novelId) {
        accessControlService.requireAccess(novelId, chatId);
        return novelRepository.findById(novelId)
                .orElseThrow(() -> new AppException("Произведение не найдено."));
    }

    private NovelStats stats(long novelId) {
        var chapters = chapterRepository.findByNovelId(novelId);
        int wordCount = chapters.stream().mapToInt(chapter -> TextTools.wordCount(chapter.text())).sum();
        int characterCount = chapters.stream().mapToInt(chapter -> chapter.text().length()).sum();
        return new NovelStats(chapters.size(), wordCount, characterCount);
    }
}

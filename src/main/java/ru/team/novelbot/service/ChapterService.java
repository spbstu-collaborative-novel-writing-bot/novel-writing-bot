package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.ChapterHistory;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.NovelRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChapterService {
    private final ChapterRepository chapterRepository;
    private final NovelRepository novelRepository;
    private final AccessControlService accessControlService;
    private final TransactionTemplate transactionTemplate;

    public ChapterService(
            ChapterRepository chapterRepository,
            NovelRepository novelRepository,
            AccessControlService accessControlService,
            TransactionTemplate transactionTemplate
    ) {
        this.chapterRepository = chapterRepository;
        this.novelRepository = novelRepository;
        this.accessControlService = accessControlService;
        this.transactionTemplate = transactionTemplate;
    }

    public Chapter addChapter(long chatId, long novelId, String title, String text) {
        accessControlService.requireAccess(novelId, chatId);
        return transactionTemplate.execute(status -> {
            long chapterId = chapterRepository.insert(novelId, title, text, chatId);
            novelRepository.touch(novelId);
            return chapterRepository.findByIdAndNovelId(chapterId, novelId).orElseThrow();
        });
    }

    public List<Chapter> listChapters(long chatId, long novelId) {
        accessControlService.requireAccess(novelId, chatId);
        return chapterRepository.findByNovelId(novelId);
    }

    public Chapter getChapter(long chatId, long novelId, long chapterId) {
        accessControlService.requireAccess(novelId, chatId);
        return chapterRepository.findByIdAndNovelId(chapterId, novelId)
                .orElseThrow(() -> new AppException("Глава не найдена."));
    }

    public String fullText(long chatId, long novelId) {
        accessControlService.requireAccess(novelId, chatId);
        List<Chapter> chapters = chapterRepository.findByNovelId(novelId);
        if (chapters.isEmpty()) {
            return "В произведении пока нет глав.";
        }
        return chapters.stream()
                .map(chapter -> "Глава " + chapter.orderNumber() + ". " + chapter.title() + "\n\n" + chapter.text())
                .collect(Collectors.joining("\n\n"));
    }

    public Chapter updateChapter(long chatId, long novelId, long chapterId, String newTitle, String newText) {
        accessControlService.requireAccess(novelId, chatId);
        return transactionTemplate.execute(status -> {
            Chapter oldChapter = chapterRepository.findByIdAndNovelId(chapterId, novelId)
                    .orElseThrow(() -> new AppException("Глава не найдена."));
            chapterRepository.saveHistory(oldChapter, chatId);
            chapterRepository.update(chapterId, newTitle, newText, chatId);
            novelRepository.touch(novelId);
            return chapterRepository.findByIdAndNovelId(chapterId, novelId).orElseThrow();
        });
    }

    public void deleteChapter(long chatId, long novelId, long chapterId) {
        accessControlService.requireAccess(novelId, chatId);
        getChapter(chatId, novelId, chapterId);
        transactionTemplate.executeWithoutResult(status -> {
            chapterRepository.delete(chapterId, novelId);
            novelRepository.touch(novelId);
        });
    }

    public List<ChapterHistory> history(long chatId, long novelId, long chapterId) {
        getChapter(chatId, novelId, chapterId);
        return chapterRepository.findHistory(chapterId);
    }
}

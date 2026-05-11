package ru.team.novelbot.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.team.novelbot.domain.AppUser;
import ru.team.novelbot.domain.Chapter;
import ru.team.novelbot.domain.ChapterDiffFragment;
import ru.team.novelbot.domain.ChapterDiffPart;
import ru.team.novelbot.domain.ChapterEditResult;
import ru.team.novelbot.domain.ChapterHistory;
import ru.team.novelbot.domain.ChapterVersion;
import ru.team.novelbot.domain.ChapterVersionDiff;
import ru.team.novelbot.domain.ChapterVersionPage;
import ru.team.novelbot.repository.ChapterRepository;
import ru.team.novelbot.repository.NovelRepository;
import ru.team.novelbot.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChapterService {
    private static final int MAX_VERSION_PAGE_SIZE = 100;
    private static final int DIFF_CONTEXT_WORDS = 5;
    private static final int MAX_LCS_CELLS = 200_000;

    private final ChapterRepository chapterRepository;
    private final NovelRepository novelRepository;
    private final UserRepository userRepository;
    private final AccessControlService accessControlService;
    private final TransactionTemplate transactionTemplate;

    public ChapterService(
            ChapterRepository chapterRepository,
            NovelRepository novelRepository,
            UserRepository userRepository,
            AccessControlService accessControlService,
            TransactionTemplate transactionTemplate
    ) {
        this.chapterRepository = chapterRepository;
        this.novelRepository = novelRepository;
        this.userRepository = userRepository;
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

    public ChapterEditResult updateChapterIfUnchanged(
            long chatId,
            long novelId,
            long chapterId,
            String newTitle,
            String newText,
            LocalDateTime expectedUpdatedAt
    ) {
        accessControlService.requireAccess(novelId, chatId);
        return transactionTemplate.execute(status -> {
            Chapter oldChapter = chapterRepository.findByIdAndNovelId(chapterId, novelId)
                    .orElseThrow(() -> new AppException("Глава не найдена."));
            if (!oldChapter.updatedAt().equals(expectedUpdatedAt)) {
                return new ChapterEditResult(false, oldChapter);
            }
            chapterRepository.saveHistory(oldChapter, chatId);
            boolean updated = chapterRepository.updateIfUnchanged(chapterId, newTitle, newText, chatId, expectedUpdatedAt);
            if (!updated) {
                Chapter current = chapterRepository.findByIdAndNovelId(chapterId, novelId).orElseThrow();
                return new ChapterEditResult(false, current);
            }
            novelRepository.touch(novelId);
            return new ChapterEditResult(true, chapterRepository.findByIdAndNovelId(chapterId, novelId).orElseThrow());
        });
    }

    public Chapter appendToChapter(long chatId, long novelId, long chapterId, String addition) {
        accessControlService.requireAccess(novelId, chatId);
        return transactionTemplate.execute(status -> {
            Chapter oldChapter = chapterRepository.findByIdAndNovelId(chapterId, novelId)
                    .orElseThrow(() -> new AppException("Глава не найдена."));
            String separator = oldChapter.text().isBlank() ? "" : "\n\n";
            chapterRepository.saveHistory(oldChapter, chatId);
            chapterRepository.update(chapterId, oldChapter.title(), oldChapter.text() + separator + addition, chatId);
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

    public ChapterVersionPage versions(long chatId, long novelId, long chapterId, int page, int size) {
        Chapter chapter = getChapter(chatId, novelId, chapterId);
        List<VersionSnapshot> snapshots = snapshots(chapter);
        Map<Long, AppUser> users = usersByChatId(snapshots.stream().map(VersionSnapshot::editorChatId).collect(Collectors.toSet()));
        List<ChapterVersion> allVersions = snapshots.stream()
                .map(snapshot -> new ChapterVersion(
                        snapshot.versionNumber(),
                        chapter.id(),
                        snapshot.title(),
                        snapshot.text(),
                        snapshot.changedAt(),
                        snapshot.editorChatId(),
                        editorName(snapshot.editorChatId(), users),
                        snapshot.current()
                ))
                .sorted(Comparator.comparingInt(ChapterVersion::versionNumber).reversed())
                .toList();
        int safeSize = Math.max(1, Math.min(size, MAX_VERSION_PAGE_SIZE));
        int safePage = Math.max(0, page);
        int from = Math.min(allVersions.size(), safePage * safeSize);
        int to = Math.min(allVersions.size(), from + safeSize);
        return new ChapterVersionPage(safePage, safeSize, allVersions.size(), allVersions.subList(from, to));
    }

    public ChapterVersionDiff versionDiff(long chatId, long novelId, long chapterId, int versionNumber) {
        Chapter chapter = getChapter(chatId, novelId, chapterId);
        List<VersionSnapshot> snapshots = snapshots(chapter);
        if (versionNumber < 1 || versionNumber > snapshots.size()) {
            throw new AppException("Версия главы не найдена.");
        }
        VersionSnapshot selected = snapshots.get(versionNumber - 1);
        VersionSnapshot previous = versionNumber == 1
                ? new VersionSnapshot(0, "", "", chapter.createdAt(), chapter.createdByChatId(), false)
                : snapshots.get(versionNumber - 2);
        Map<Long, AppUser> users = usersByChatId(Set.of(selected.editorChatId()));
        boolean titleChanged = !Objects.equals(previous.title(), selected.title());
        boolean textChanged = !Objects.equals(previous.text(), selected.text());
        return new ChapterVersionDiff(
                selected.versionNumber(),
                previous.versionNumber(),
                selected.current(),
                selected.versionNumber() == 1,
                selected.changedAt(),
                editorName(selected.editorChatId(), users),
                titleChanged,
                titleChanged ? diffFragments(previous.title(), selected.title()) : List.of(),
                textChanged ? diffFragments(previous.text(), selected.text()) : List.of()
        );
    }

    private List<VersionSnapshot> snapshots(Chapter chapter) {
        List<ChapterHistory> history = chapterRepository.findHistory(chapter.id()).stream()
                .sorted(Comparator.comparing(ChapterHistory::changedAt).thenComparing(ChapterHistory::id))
                .toList();
        if (history.isEmpty()) {
            return List.of(new VersionSnapshot(
                    1,
                    chapter.title(),
                    chapter.text(),
                    chapter.createdAt(),
                    chapter.createdByChatId(),
                    true
            ));
        }
        List<VersionSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            ChapterHistory item = history.get(i);
            ChapterHistory creator = i == 0 ? null : history.get(i - 1);
            snapshots.add(new VersionSnapshot(
                    i + 1,
                    item.oldTitle(),
                    item.oldText(),
                    creator == null ? chapter.createdAt() : creator.changedAt(),
                    creator == null ? chapter.createdByChatId() : creator.editorChatId(),
                    false
            ));
        }
        snapshots.add(new VersionSnapshot(
                history.size() + 1,
                chapter.title(),
                chapter.text(),
                chapter.updatedAt(),
                chapter.lastEditorChatId(),
                true
        ));
        return snapshots;
    }

    private Map<Long, AppUser> usersByChatId(Set<Long> chatIds) {
        return userRepository.findByChatIds(chatIds);
    }

    private String editorName(long chatId, Map<Long, AppUser> users) {
        AppUser user = users.get(chatId);
        if (user != null && user.username() != null && !user.username().isBlank()) {
            return "@" + user.username();
        }
        if (user != null && user.displayName() != null && !user.displayName().isBlank()) {
            return user.displayName();
        }
        return "id " + chatId;
    }

    private List<ChapterDiffFragment> diffFragments(String oldText, String newText) {
        List<String> oldWords = words(oldText);
        List<String> newWords = words(newText);
        if ((long) oldWords.size() * (long) newWords.size() > MAX_LCS_CELLS) {
            return fallbackDiffFragments(oldWords, newWords);
        }
        return fragmentsFromOps(lcsOps(oldWords, newWords));
    }

    private List<String> words(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.trim().split("\\s+"));
    }

    private List<WordOp> lcsOps(List<String> oldWords, List<String> newWords) {
        int[][] dp = new int[oldWords.size() + 1][newWords.size() + 1];
        for (int i = oldWords.size() - 1; i >= 0; i--) {
            for (int j = newWords.size() - 1; j >= 0; j--) {
                if (oldWords.get(i).equals(newWords.get(j))) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        List<WordOp> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < oldWords.size() || j < newWords.size()) {
            if (i < oldWords.size() && j < newWords.size() && oldWords.get(i).equals(newWords.get(j))) {
                ops.add(new WordOp(ChapterDiffPart.EQUAL, oldWords.get(i)));
                i++;
                j++;
            } else if (i < oldWords.size() && (j == newWords.size() || dp[i + 1][j] >= dp[i][j + 1])) {
                ops.add(new WordOp(ChapterDiffPart.REMOVED, oldWords.get(i++)));
            } else {
                ops.add(new WordOp(ChapterDiffPart.ADDED, newWords.get(j++)));
            }
        }
        return ops;
    }

    private List<ChapterDiffFragment> fragmentsFromOps(List<WordOp> ops) {
        List<int[]> ranges = new ArrayList<>();
        int index = 0;
        while (index < ops.size()) {
            while (index < ops.size() && ChapterDiffPart.EQUAL.equals(ops.get(index).type())) {
                index++;
            }
            if (index >= ops.size()) {
                break;
            }
            int changeStart = index;
            while (index < ops.size() && !ChapterDiffPart.EQUAL.equals(ops.get(index).type())) {
                index++;
            }
            int changeEnd = index;
            int start = Math.max(0, changeStart - DIFF_CONTEXT_WORDS);
            int end = Math.min(ops.size(), changeEnd + DIFF_CONTEXT_WORDS);
            if (!ranges.isEmpty() && start <= ranges.get(ranges.size() - 1)[1]) {
                ranges.get(ranges.size() - 1)[1] = end;
            } else {
                ranges.add(new int[]{start, end});
            }
        }
        return ranges.stream()
                .map(range -> fragment(ops, range[0], range[1]))
                .toList();
    }

    private ChapterDiffFragment fragment(List<WordOp> ops, int start, int end) {
        List<ChapterDiffPart> parts = new ArrayList<>();
        String type = null;
        List<String> bucket = new ArrayList<>();
        for (int i = start; i < end; i++) {
            WordOp op = ops.get(i);
            if (type != null && !type.equals(op.type())) {
                parts.add(new ChapterDiffPart(type, String.join(" ", bucket)));
                bucket.clear();
            }
            type = op.type();
            bucket.add(op.word());
        }
        if (type != null && !bucket.isEmpty()) {
            parts.add(new ChapterDiffPart(type, String.join(" ", bucket)));
        }
        return new ChapterDiffFragment(parts);
    }

    private List<ChapterDiffFragment> fallbackDiffFragments(List<String> oldWords, List<String> newWords) {
        int prefix = 0;
        int maxPrefix = Math.min(oldWords.size(), newWords.size());
        while (prefix < maxPrefix && oldWords.get(prefix).equals(newWords.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldWords.size() - prefix
                && suffix < newWords.size() - prefix
                && oldWords.get(oldWords.size() - 1 - suffix).equals(newWords.get(newWords.size() - 1 - suffix))) {
            suffix++;
        }
        List<ChapterDiffPart> parts = new ArrayList<>();
        int beforeStart = Math.max(0, prefix - DIFF_CONTEXT_WORDS);
        if (beforeStart < prefix) {
            parts.add(new ChapterDiffPart(ChapterDiffPart.EQUAL, joinWords(newWords, beforeStart, prefix)));
        }
        if (prefix < oldWords.size() - suffix) {
            parts.add(new ChapterDiffPart(ChapterDiffPart.REMOVED, compactWords(oldWords, prefix, oldWords.size() - suffix)));
        }
        if (prefix < newWords.size() - suffix) {
            parts.add(new ChapterDiffPart(ChapterDiffPart.ADDED, compactWords(newWords, prefix, newWords.size() - suffix)));
        }
        int afterEnd = Math.min(newWords.size(), newWords.size() - suffix + DIFF_CONTEXT_WORDS);
        if (newWords.size() - suffix < afterEnd) {
            parts.add(new ChapterDiffPart(ChapterDiffPart.EQUAL, joinWords(newWords, newWords.size() - suffix, afterEnd)));
        }
        return List.of(new ChapterDiffFragment(parts));
    }

    private String compactWords(List<String> words, int start, int end) {
        int maxWords = 80;
        if (end - start <= maxWords) {
            return joinWords(words, start, end);
        }
        return joinWords(words, start, start + maxWords) + " ...";
    }

    private String joinWords(List<String> words, int start, int end) {
        return String.join(" ", words.subList(start, end));
    }

    private record VersionSnapshot(
            int versionNumber,
            String title,
            String text,
            LocalDateTime changedAt,
            long editorChatId,
            boolean current
    ) {
    }

    private record WordOp(String type, String word) {
    }
}

package ru.team.novelbot.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ChapterVersionDiff(
        int versionNumber,
        int previousVersionNumber,
        boolean current,
        boolean firstVersion,
        LocalDateTime changedAt,
        String editorName,
        boolean titleChanged,
        List<ChapterDiffFragment> titleFragments,
        List<ChapterDiffFragment> textFragments
) {
}

package ru.team.novelbot.domain;

import java.util.List;

public record NovelDetails(
        Novel novel,
        AppUser owner,
        List<NovelAuthor> authors,
        NovelStats stats
) {
    public int chapterCount() {
        return stats.chapterCount();
    }
}

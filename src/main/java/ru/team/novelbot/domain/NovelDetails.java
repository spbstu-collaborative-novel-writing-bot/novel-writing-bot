package ru.team.novelbot.domain;

import java.util.List;

public record NovelDetails(
        Novel novel,
        AppUser owner,
        List<NovelAuthor> authors,
        int chapterCount
) {
}

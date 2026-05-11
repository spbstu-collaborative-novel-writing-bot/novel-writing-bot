package ru.team.novelbot.domain;

import java.util.List;

public record ChapterVersionPage(
        int page,
        int size,
        int total,
        List<ChapterVersion> versions
) {
}

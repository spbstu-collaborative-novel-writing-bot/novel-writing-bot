package ru.team.novelbot.domain;

public record ChapterDiffPart(
        String type,
        String text
) {
    public static final String EQUAL = "equal";
    public static final String ADDED = "added";
    public static final String REMOVED = "removed";
}

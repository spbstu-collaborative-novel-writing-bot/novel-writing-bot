package ru.team.novelbot.domain;

public enum AuthorType {
    OWNER("владелец"),
    CO_AUTHOR("соавтор");

    private final String displayName;

    AuthorType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

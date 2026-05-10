package ru.team.novelbot.domain;

public enum UserRole {
    USER("User"),
    ADMIN("Admin");

    private final String displayName;

    UserRole(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static UserRole fromDb(String value) {
        return UserRole.valueOf(value);
    }
}

package ru.team.novelbot.domain;

public enum UserRole {
    USER,
    ADMIN;

    public static UserRole fromDatabase(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        return UserRole.valueOf(value.trim().toUpperCase());
    }
}

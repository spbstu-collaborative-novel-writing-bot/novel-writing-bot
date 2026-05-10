package ru.team.novelbot.service;

public class AccessDeniedException extends AppException {
    public AccessDeniedException(String message) {
        super(message);
    }
}

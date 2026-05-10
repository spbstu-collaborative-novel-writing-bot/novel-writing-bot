package ru.team.novelbot.telegram;

import java.util.ArrayList;
import java.util.List;

public record TelegramResponse(List<TelegramAction> actions) {
    public static TelegramResponse of(TelegramAction action) {
        return new TelegramResponse(List.of(action));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<TelegramAction> actions = new ArrayList<>();

        public Builder add(TelegramAction action) {
            actions.add(action);
            return this;
        }

        public TelegramResponse build() {
            return new TelegramResponse(List.copyOf(actions));
        }
    }
}

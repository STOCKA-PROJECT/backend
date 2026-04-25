package com.stocka.backend.modules.users.entity;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum Language {
    ES,
    EN,
    CA;

    public Locale toLocale() {
        return switch (this) {
            case ES -> Locale.of("es");
            case EN -> Locale.ENGLISH;
            case CA -> Locale.of("ca");
        };
    }

    public static Language fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return ES;
        }
        try {
            return Language.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idioma no soportado: " + raw + ". Soportados: ES, EN, CA"
            );
        }
    }
}

package com.stocka.backend.modules.pieces.importexport.service;

import java.util.List;

/**
 * Carries one or more human-readable (Spanish) validation messages for a single import row.
 * Thrown by the reference resolver and the per-row pipeline; caught by the import service, which
 * turns it into an {@code ERROR} row in the report instead of aborting the whole request.
 */
public class RowValidationException extends RuntimeException {

    private final transient List<String> messages;

    /**
     * Creates the exception with a single message.
     *
     * @param message the validation message
     */
    public RowValidationException(String message) {
        this(List.of(message));
    }

    /**
     * Creates the exception with several messages.
     *
     * @param messages the validation messages; copied defensively
     */
    public RowValidationException(List<String> messages) {
        super(messages.isEmpty() ? "validation error" : messages.get(0));
        this.messages = List.copyOf(messages);
    }

    /**
     * @return the validation messages, never empty
     */
    public List<String> getMessages() {
        return messages;
    }
}

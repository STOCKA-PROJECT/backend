package com.stocka.backend.modules.notifications.dispatch;

import java.util.function.Supplier;

/**
 * Thread-local flag that suppresses resource lifecycle notifications for the current thread.
 *
 * <p>Used when replaying offline mutations during sync push: draining a client's outbox can apply
 * dozens of creates/edits/deletes at once, and emailing for each would flood subscribers with
 * notifications for changes they already made locally. The flag is read at <em>publish</em> time
 * (on the request thread, where it is set) and stamped onto the {@link
 * com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent}, because the dispatch
 * listener runs asynchronously after commit on a different thread where the thread-local would no
 * longer be visible.
 *
 * @since 0.3.0
 */
public final class NotificationSuppressionContext {

    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private NotificationSuppressionContext() {
    }

    /**
     * Whether lifecycle notifications are currently suppressed on this thread.
     *
     * @return {@code true} while running inside {@link #runSuppressed(Supplier)} or {@link
     *         #runSuppressed(Runnable)}
     */
    public static boolean isSuppressed() {
        return Boolean.TRUE.equals(SUPPRESSED.get());
    }

    /**
     * Runs {@code action} with lifecycle notifications suppressed, restoring the previous state
     * afterwards (supports nesting).
     *
     * @param action the work to run with suppression enabled
     */
    public static void runSuppressed(Runnable action) {
        runSuppressed(() -> {
            action.run();
            return null;
        });
    }

    /**
     * Runs {@code action} with lifecycle notifications suppressed and returns its result, restoring
     * the previous state afterwards (supports nesting).
     *
     * @param action the work to run with suppression enabled
     * @param <T>    the result type
     * @return the value produced by {@code action}
     */
    public static <T> T runSuppressed(Supplier<T> action) {
        boolean previous = isSuppressed();
        SUPPRESSED.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            SUPPRESSED.set(previous);
        }
    }
}

package com.stocka.backend.modules.notifications.email;

import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.users.entity.Language;

public interface EmailService {
    void sendInvitationEmail(String to, String inviterName, String orgName, String acceptUrl, Language language);

    void sendPasswordResetEmail(String to, String userName, String resetUrl, Language language);

    void sendEmailVerification(String to, String userName, String verifyUrl, Language language);

    /**
     * Send a coalesced lifecycle notification (one email per resource per quiet window).
     *
     * @param to            recipient address
     * @param kind          resource kind
     * @param action        effective action emerging from the coalescing reduction
     * @param resourceName  latest snapshot of the resource name
     * @param orgName       organization display name
     * @param actorName     display name of the latest actor
     * @param resourceUrl   absolute URL pointing to the resource (or to the dashboard root
     *                      when the resource was deleted)
     * @param language      recipient locale; never {@code null}
     */
    void sendResourceLifecycleEmail(
            String to,
            ResourceKind kind,
            LifecycleAction action,
            String resourceName,
            String orgName,
            String actorName,
            String resourceUrl,
            Language language
    );
}

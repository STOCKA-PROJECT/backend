package com.stocka.backend.modules.notifications.email;

import com.stocka.backend.modules.users.entity.Language;

public interface EmailService {
    void sendInvitationEmail(String to, String inviterName, String orgName, String acceptUrl, Language language);

    void sendPasswordResetEmail(String to, String userName, String resetUrl, Language language);

    void sendEmailVerification(String to, String userName, String verifyUrl, Language language);
}

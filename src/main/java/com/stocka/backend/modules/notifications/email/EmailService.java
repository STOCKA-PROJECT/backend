package com.stocka.backend.modules.notifications.email;

public interface EmailService {
    void sendInvitationEmail(String to, String inviterName, String orgName, String acceptUrl);
}

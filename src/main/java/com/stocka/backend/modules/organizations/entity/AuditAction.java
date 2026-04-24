package com.stocka.backend.modules.organizations.entity;

public enum AuditAction {
    ORG_CREATED,
    ORG_UPDATED,
    ORG_DELETED,
    MEMBER_INVITED,
    INVITATION_ACCEPTED,
    INVITATION_REJECTED,
    INVITATION_CANCELLED,
    MEMBER_REMOVED,
    MEMBER_LEFT,
    MEMBER_ROLE_CHANGED
}

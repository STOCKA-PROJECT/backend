package com.stocka.backend.modules.mcp.tool.support;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.mcp.tool.McpCaller;
import com.stocka.backend.modules.mcp.tool.ToolException;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.organizations.service.OrganizationService;

/**
 * Resolves the organization a tool works on and applies the same role rules as the REST
 * controllers ({@link OrganizationSecurity}), failing with a readable {@link ToolException}.
 */
@Component
public class OrgAccess {

    private final OrganizationRepository organizations;
    private final OrganizationSecurity security;
    private final OrganizationService organizationService;

    public OrgAccess(OrganizationRepository organizations, OrganizationSecurity security,
                     OrganizationService organizationService) {
        this.organizations = organizations;
        this.security = security;
        this.organizationService = organizationService;
    }

    /** Any member (including SPECTATOR). */
    public Organization read(McpCaller caller, String slug) {
        Organization org = resolve(slug);
        require(security.canReadOrgContent(org.getSlug(), caller.user()), "any member of the organization");
        return org;
    }

    /** OWNER, MANAGER or USER (pieces, timelines, contacts). */
    public Organization writePieces(McpCaller caller, String slug) {
        Organization org = resolve(slug);
        require(security.canWritePieces(org.getSlug(), caller.user()), "OWNER, MANAGER or USER");
        return org;
    }

    /** OWNER or MANAGER (locations, piece types, organization attributes, search reindex). */
    public Organization manage(McpCaller caller, String slug) {
        Organization org = resolve(slug);
        require(security.canManageOrgContent(org.getSlug(), caller.user()), "OWNER or MANAGER");
        return org;
    }

    /** OWNER only (members, invitations). */
    public Organization owner(McpCaller caller, String slug) {
        Organization org = resolve(slug);
        require(security.isOwner(org.getSlug(), caller.user()), "OWNER");
        return org;
    }

    /** Reading ports and type actions (private feature of admin-owned organizations). */
    public Organization readHardware(McpCaller caller, String slug) {
        Organization org = resolve(slug);
        require(security.canReadPorts(org.getSlug(), caller.user()),
                "a member of an organization where the hardware feature is enabled");
        return org;
    }

    /** Managing ports and type actions. */
    public Organization manageHardware(McpCaller caller, String slug) {
        Organization org = resolve(slug);
        require(security.canManagePorts(org.getSlug(), caller.user()),
                "OWNER or MANAGER of an organization where the hardware feature is enabled");
        return org;
    }

    /** @return the caller's role in {@code org}, if a member */
    public Optional<OrganizationRoleEnum> roleOf(McpCaller caller, Organization org) {
        return organizationService.getCurrentUserRole(org, caller.user());
    }

    /** @return the organization for a current slug */
    public Organization resolve(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ToolException(ErrorCodes.MCP_INVALID_ARGUMENTS, null,
                    "Pass the organization slug (see the `me` tool).", Map.of("detail", "org is required"));
        }
        return organizations.findBySlug(slug.trim())
                .orElseThrow(() -> new ToolException("organizations.not_found", "Organization not found: " + slug,
                        null, Map.of("org", slug)));
    }

    private static void require(boolean allowed, String neededRole) {
        if (!allowed) {
            throw new ToolException(ErrorCodes.AUTH_FORBIDDEN, null,
                    "This action needs the role " + neededRole + "; the user does not have it in this organization.",
                    Map.of("neededRole", neededRole));
        }
    }
}

package com.stocka.backend.modules.mcp.tool.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.mcp.tool.McpCaller;
import com.stocka.backend.modules.mcp.tool.ToolException;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.users.entity.User;

@DisplayName("OrgAccess")
class OrgAccessTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationSecurity security = mock(OrganizationSecurity.class);
    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final OrgAccess access = new OrgAccess(organizations, security, organizationService);
    private final User user = new User().setEmail("u@x");
    private final McpCaller caller = new McpCaller(user, Set.of(OAuthScope.READ), "Claude");
    private final Organization acme = new Organization().setId(1).setName("Acme");

    @BeforeEach
    void setUp() {
        acme.setSlug("acme");
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(acme));
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("unknown slugs and blank slugs are readable errors")
        void unknown() {
            assertThatThrownBy(() -> access.resolve("nope")).isInstanceOf(ToolException.class)
                    .satisfies(e -> assertThat(((ToolException) e).getCode()).isEqualTo("organizations.not_found"));
            assertThatThrownBy(() -> access.resolve(" ")).isInstanceOf(ToolException.class)
                    .satisfies(e -> assertThat(((ToolException) e).getCode()).isEqualTo(ErrorCodes.MCP_INVALID_ARGUMENTS));
        }

        @Test
        @DisplayName("slugs are trimmed")
        void trimmed() {
            assertThat(access.resolve(" acme ")).isSameAs(acme);
        }
    }

    @Nested
    @DisplayName("role checks")
    class Roles {

        @Test
        @DisplayName("each level delegates to the matching OrganizationSecurity rule")
        void delegates() {
            when(security.canReadOrgContent("acme", user)).thenReturn(true);
            when(security.canWritePieces("acme", user)).thenReturn(false);
            when(security.canManageOrgContent("acme", user)).thenReturn(true);
            when(security.isOwner("acme", user)).thenReturn(false);
            when(security.canReadPorts("acme", user)).thenReturn(true);
            when(security.canManagePorts("acme", user)).thenReturn(false);

            assertThat(access.read(caller, "acme")).isSameAs(acme);
            assertThat(access.manage(caller, "acme")).isSameAs(acme);
            assertThat(access.readHardware(caller, "acme")).isSameAs(acme);
            assertThatThrownBy(() -> access.writePieces(caller, "acme")).isInstanceOf(ToolException.class)
                    .satisfies(e -> {
                        ToolException te = (ToolException) e;
                        assertThat(te.getCode()).isEqualTo(ErrorCodes.AUTH_FORBIDDEN);
                        assertThat(te.getHint()).contains("OWNER, MANAGER or USER");
                    });
            assertThatThrownBy(() -> access.owner(caller, "acme")).hasMessageContaining(ErrorCodes.AUTH_FORBIDDEN);
            assertThatThrownBy(() -> access.manageHardware(caller, "acme")).isInstanceOf(ToolException.class);
        }

        @Test
        @DisplayName("the role of the caller comes from the organization service")
        void role() {
            when(organizationService.getCurrentUserRole(eq(acme), any())).thenReturn(Optional.of(OrganizationRoleEnum.MANAGER));
            assertThat(access.roleOf(caller, acme)).contains(OrganizationRoleEnum.MANAGER);
        }
    }
}

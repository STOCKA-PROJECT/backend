package com.stocka.backend.modules.mcp.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.mcp.resource.ResourceSource;
import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.dto.CreateInvitationDto;
import com.stocka.backend.modules.organizations.dto.MemberResponseDto;
import com.stocka.backend.modules.organizations.dto.OrganizationUsageDto;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationInvitation;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.organizations.service.OrganizationInvitationService;
import com.stocka.backend.modules.organizations.service.OrganizationMemberService;
import com.stocka.backend.modules.pieces.dto.PieceFilterCriteria;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.service.OrganizationUsageService;
import com.stocka.backend.modules.pieces.service.PieceService;
import com.stocka.backend.modules.locations.service.LocationService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.search.dto.SearchStatusDto;
import com.stocka.backend.modules.search.service.SemanticSearchService;
import com.stocka.backend.modules.timelines.service.TimelineService;

/**
 * Organization-level tools: details and quotas, members and invitations, semantic search index.
 * Also serves the {@code stocka://org/{slug}/overview} resource.
 */
@Component
public class OrganizationTools implements ToolSource, ResourceSource {

    public record OrgInput(@Param(value = "Organization slug", required = true) String org) {
    }

    public record Usage(
            @Param("Pieces in the organization") long pieces,
            @Param("Maximum pieces allowed by the plan") long maxPieces,
            @Param("Attachment storage used, in bytes") long bytes,
            @Param("Attachment storage allowed, in bytes") long maxBytes) {
    }

    public record SearchStatus(
            @Param("Whether semantic search is configured on the server") boolean enabled,
            @Param("Whether this organization has an index") boolean indexed,
            @Param("Pieces currently indexed") long indexedPieces,
            @Param("Active pieces that should be indexed") long totalPieces,
            @Param("Whether a training run is pending (new pieces match by text only until it runs)") boolean trainingPending) {
    }

    public record OrgDetail(
            @Param("Organization id") Integer id,
            @Param("Name") String name,
            @Param("Slug") String slug,
            @Param("The caller's role") String role,
            @Param("Whether ports and type actions are available") boolean hardwareEnabled,
            @Param("Quota usage") Usage usage,
            @Param("Semantic search index status") SearchStatus search) {
    }

    public record InviteInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Email of the person to invite", required = true) String email,
            @Param(value = "Role to grant: OWNER, MANAGER, USER or SPECTATOR", required = true) OrganizationRoleEnum role) {
    }

    public record InvitationView(
            @Param("Invitation id") Integer id,
            @Param("Invited email") String email,
            @Param("Role") String role,
            @Param("Status") String status) {
    }

    public record MemberRoleInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Member id (from org_members_list, not the user id)", required = true) Integer memberId,
            @Param(value = "New role: OWNER, MANAGER, USER or SPECTATOR", required = true) OrganizationRoleEnum role) {
    }

    public record MemberInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Member id (from org_members_list)", required = true) Integer memberId) {
    }

    private final OrgAccess access;
    private final OrganizationSecurity security;
    private final OrganizationUsageService usage;
    private final SemanticSearchService search;
    private final OrganizationMemberService members;
    private final OrganizationInvitationService invitations;
    private final PieceService pieces;
    private final PieceTypeService pieceTypes;
    private final LocationService locations;
    private final TimelineService timelines;

    public OrganizationTools(OrgAccess access, OrganizationSecurity security, OrganizationUsageService usage,
                             SemanticSearchService search, OrganizationMemberService members,
                             OrganizationInvitationService invitations, PieceService pieces,
                             PieceTypeService pieceTypes, LocationService locations, TimelineService timelines) {
        this.access = access;
        this.security = security;
        this.usage = usage;
        this.search = search;
        this.members = members;
        this.invitations = invitations;
        this.pieces = pieces;
        this.pieceTypes = pieceTypes;
        this.locations = locations;
        this.timelines = timelines;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("org_get",
                        "Details of an organization: the caller's role, quota usage (pieces and storage), "
                                + "whether hardware features are enabled and the state of the semantic search index.",
                        OrgInput.class, OAuthScope.READ, ToolHints.READ, this::get).returning(OrgDetail.class),
                ToolDefinition.of("org_members_list",
                        "Members of an organization with their role. Member ids are what org_member_set_role and "
                                + "org_member_remove expect.",
                        OrgInput.class, OAuthScope.READ, ToolHints.READ, this::listMembers),
                ToolDefinition.of("org_invite",
                        "Invites someone by email to the organization with a role; they receive an email with the "
                                + "link. Needs OWNER or MANAGER.",
                        InviteInput.class, OAuthScope.ORG_MANAGE, ToolHints.CREATE, this::invite)
                        .returning(InvitationView.class),
                ToolDefinition.of("org_member_set_role",
                        "Changes the role of a member. Needs OWNER.",
                        MemberRoleInput.class, OAuthScope.ORG_MANAGE, ToolHints.UPDATE, this::setRole),
                ToolDefinition.of("org_member_remove",
                        "Removes a member from the organization (their pieces stay). Needs OWNER or MANAGER.",
                        MemberInput.class, OAuthScope.ORG_MANAGE, ToolHints.DELETE, this::removeMember)
                        .returning(ToolResults.Done.class),
                ToolDefinition.of("search_reindex",
                        "Rebuilds the semantic search index of the organization (use after renaming types or "
                                + "locations, or when search results look stale). Needs OWNER or MANAGER.",
                        OrgInput.class, OAuthScope.ORG_MANAGE, ToolHints.UPDATE, this::reindex)
                        .returning(SearchStatus.class));
    }

    @Override
    public List<ResourceTemplateDefinition> templates() {
        return List.of(new ResourceTemplateDefinition("stocka://org/{slug}/overview", "Organization overview",
                "Counts of pieces (by status), types, locations and timelines of an organization: a cheap first look.",
                "text/markdown", (caller, vars) -> overview(caller, vars.get("slug"))));
    }

    private OrgDetail get(ToolCall<OrgInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        return detail(call.caller(), org);
    }

    OrgDetail detail(McpCaller caller, Organization org) {
        String role = access.roleOf(caller, org).map(Enum::name).orElse(
                OrganizationSecurity.isGlobalAdmin(caller.user()) ? "ADMIN" : "");
        OrganizationUsageDto u = usage.getUsage(org.getId());
        SearchStatusDto s = search.status(org);
        return new OrgDetail(org.getId(), org.getName(), org.getSlug(), role, security.portsEnabled(org, caller.user()),
                new Usage(u.pieces(), u.maxPieces(), u.bytes(), u.maxBytes()),
                new SearchStatus(s.enabled(), s.indexed(), s.indexedPieces(), s.totalPieces(), s.trainingPending()));
    }

    private Object listMembers(ToolCall<OrgInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        List<MemberResponseDto> out = new ArrayList<>();
        for (OrganizationMember member : members.listMembers(org.getId())) {
            out.add(MemberResponseDto.from(member));
        }
        return Map.of("items", out);
    }

    private InvitationView invite(ToolCall<InviteInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        CreateInvitationDto dto = new CreateInvitationDto();
        dto.setEmail(call.input().email());
        dto.setRole(call.input().role());
        OrganizationInvitation invitation = invitations.createInvitation(org.getId(), dto, call.caller().user());
        return new InvitationView(invitation.getId(), invitation.getEmail(), invitation.getRole().name(),
                invitation.getStatus().name());
    }

    private Object setRole(ToolCall<MemberRoleInput> call) {
        Organization org = access.owner(call.caller(), call.input().org());
        OrganizationMember member = members.updateMemberRole(org.getId(), call.input().memberId(),
                call.input().role(), call.caller().user());
        return MemberResponseDto.from(member);
    }

    private ToolResults.Done removeMember(ToolCall<MemberInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        members.removeMember(org.getId(), call.input().memberId(), call.caller().user());
        return ToolResults.Done.of("Member " + call.input().memberId() + " removed from " + org.getSlug());
    }

    private SearchStatus reindex(ToolCall<OrgInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        SearchStatusDto s = search.reindex(org);
        return new SearchStatus(s.enabled(), s.indexed(), s.indexedPieces(), s.totalPieces(), s.trainingPending());
    }

    private String overview(McpCaller caller, String slug) {
        Organization org = access.read(caller, slug);
        long active = count(org, PieceStatus.ACTIVE);
        long pending = count(org, PieceStatus.PENDING);
        int types = pieceTypes.listAll(org.getId()).size();
        int locationCount = locations.listAll(org.getId()).size();
        int timelineCount = timelines.listAll(org.getId()).size();
        OrgDetail d = detail(caller, org);
        return "# " + org.getName() + " (`" + org.getSlug() + "`)\n\n"
                + "- Your role: " + d.role() + "\n"
                + "- Pieces: " + (active + pending) + " (" + active + " ACTIVE, " + pending + " PENDING)"
                + " of " + d.usage().maxPieces() + " allowed\n"
                + "- Piece types: " + types + "\n"
                + "- Locations: " + locationCount + "\n"
                + "- Timelines: " + timelineCount + "\n"
                + "- Hardware features: " + (d.hardwareEnabled() ? "enabled" : "not available") + "\n"
                + "- Semantic search: " + (d.search().enabled() && d.search().indexed() ? "indexed ("
                + d.search().indexedPieces() + "/" + d.search().totalPieces() + " pieces)" : "off") + "\n";
    }

    private long count(Organization org, PieceStatus status) {
        PieceFilterCriteria criteria = new PieceFilterCriteria(null, null, null, null, status, null, List.of());
        return pieces.list(org.getId(), criteria, PageRequest.of(0, 1)).getTotalElements();
    }
}

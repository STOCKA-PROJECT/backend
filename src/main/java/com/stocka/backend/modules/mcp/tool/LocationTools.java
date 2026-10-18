package com.stocka.backend.modules.mcp.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.locations.dto.CreateLocationDto;
import com.stocka.backend.modules.locations.dto.LocationBreadcrumbItemDto;
import com.stocka.backend.modules.locations.dto.UpdateLocationDto;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.service.LocationService;
import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.repository.PieceRepository;

/**
 * The location tree (warehouse → shelf → box) and its edits.
 */
@Component
public class LocationTools implements ToolSource {

    public record OrgInput(@Param(value = "Organization slug", required = true) String org) {
    }

    public record LocationNode(
            @Param("Location id") Integer id,
            @Param("Name") String name,
            @Param("Description") String description,
            @Param("Parent location id, null for a root") Integer parentId,
            @Param("Full path from the root, segments joined with ' / '") String path,
            @Param("Pieces placed directly in this location") long pieceCount) {
    }

    public record TreeResult(@Param("Every location of the organization, parents before children") List<LocationNode> items) {
    }

    public record CreateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Name, unique among siblings", required = true) String name,
            @Param("Description") String description,
            @Param("Parent location id; omit to create a root location") Integer parentId) {
    }

    public record UpdateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Location id", required = true) Integer locationId,
            @Param("New name") String name,
            @Param("New description") String description,
            @Param("Move under this parent (cannot be itself or a descendant)") Integer parentId,
            @Param("Move to the root level") Boolean moveToRoot) {
    }

    public record LocationInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Location id", required = true) Integer locationId) {
    }

    private final OrgAccess access;
    private final LocationService locations;
    private final PieceRepository pieces;

    public LocationTools(OrgAccess access, LocationService locations, PieceRepository pieces) {
        this.access = access;
        this.locations = locations;
        this.pieces = pieces;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("locations_tree",
                        "Every location of the organization as a flat list with parent ids, full paths and how many "
                                + "pieces sit directly in each. Use the ids for piece locations and filters.",
                        OrgInput.class, OAuthScope.READ, ToolHints.READ, this::tree).returning(TreeResult.class),
                ToolDefinition.of("location_create",
                        "Creates a location, as a root or under a parent. Needs OWNER or MANAGER.",
                        CreateInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.CREATE, this::create)
                        .returning(LocationNode.class),
                ToolDefinition.of("location_update",
                        "Renames, describes or moves a location (parentId or moveToRoot). Needs OWNER or MANAGER.",
                        UpdateInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.UPDATE, this::update)
                        .returning(LocationNode.class),
                ToolDefinition.of("location_delete",
                        "Deletes an empty location (no pieces, no children). Needs OWNER or MANAGER and the delete scope.",
                        LocationInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::delete)
                        .returning(ToolResults.Done.class));
    }

    private TreeResult tree(ToolCall<OrgInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        List<Location> all = locations.listAll(org.getId());
        Map<Integer, Location> byId = new HashMap<>();
        for (Location l : all) {
            byId.put(l.getId(), l);
        }
        List<LocationNode> nodes = new ArrayList<>();
        for (Location l : all) {
            nodes.add(new LocationNode(l.getId(), l.getName(), l.getDescription(),
                    l.getParent() == null ? null : l.getParent().getId(), pathOf(l, byId), pieces.countByLocation(l)));
        }
        nodes.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        return new TreeResult(nodes);
    }

    private static String pathOf(Location location, Map<Integer, Location> byId) {
        List<String> segments = new ArrayList<>();
        Location current = location;
        int guard = 0;
        while (current != null && guard++ < 64) {
            segments.add(0, current.getName());
            current = current.getParent() == null ? null : byId.get(current.getParent().getId());
        }
        return String.join(" / ", segments);
    }

    private LocationNode create(ToolCall<CreateInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        CreateLocationDto dto = new CreateLocationDto();
        dto.setName(call.input().name());
        dto.setDescription(call.input().description());
        dto.setParentId(call.input().parentId());
        return node(locations.create(org.getId(), dto));
    }

    private LocationNode update(ToolCall<UpdateInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        UpdateLocationDto dto = new UpdateLocationDto();
        dto.setName(call.input().name());
        dto.setDescription(call.input().description());
        dto.setParentId(call.input().parentId());
        dto.setMoveToRoot(call.input().moveToRoot());
        return node(locations.update(org.getId(), call.input().locationId(), dto));
    }

    private ToolResults.Done delete(ToolCall<LocationInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        locations.softDelete(org.getId(), call.input().locationId());
        return ToolResults.Done.of("Location " + call.input().locationId() + " deleted");
    }

    private LocationNode node(Location l) {
        List<String> segments = new ArrayList<>();
        for (LocationBreadcrumbItemDto item : locations.breadcrumb(l)) {
            segments.add(item.name());
        }
        return new LocationNode(l.getId(), l.getName(), l.getDescription(),
                l.getParent() == null ? null : l.getParent().getId(), String.join(" / ", segments),
                pieces.countByLocation(l));
    }
}

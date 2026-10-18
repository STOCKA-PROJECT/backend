package com.stocka.backend.modules.mcp.tool.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.mcp.tool.ToolException;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.service.OrganizationPieceAttributeService;
import com.stocka.backend.modules.pieces.dto.AttributeScope;
import com.stocka.backend.modules.pieces.dto.AttributeValueInputDto;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;

/**
 * Lets tools reference attributes by name as well as by id.
 *
 * <p>A reference is matched, case-insensitively, against the id, the technical name and the label of
 * the attributes of the given piece types, then of the organization-wide attributes. Zero or several
 * matches raise a readable error listing the candidates.
 */
@Component
public class AttributeValueResolver {

    /** One value as tools receive it. */
    public record ValueInput(String attribute, String scope, String value) {
    }

    private final PieceTypeService types;
    private final OrganizationPieceAttributeService orgAttributes;

    public AttributeValueResolver(PieceTypeService types, OrganizationPieceAttributeService orgAttributes) {
        this.types = types;
        this.orgAttributes = orgAttributes;
    }

    /**
     * @param orgId    organization
     * @param typeIds  piece types whose attributes are in scope
     * @param inputs   values to resolve (may be {@code null})
     * @return the service DTOs, in the same order
     */
    public List<AttributeValueInputDto> resolve(Integer orgId, Collection<Integer> typeIds, List<ValueInput> inputs) {
        List<AttributeValueInputDto> out = new ArrayList<>();
        if (inputs == null) return out;
        List<PieceTypeAttribute> typeAttrs = new ArrayList<>();
        Set<Integer> seenTypes = new LinkedHashSet<>(typeIds == null ? List.of() : typeIds);
        for (Integer typeId : seenTypes) {
            PieceType type = types.findInOrg(orgId, typeId);
            typeAttrs.addAll(types.attributesOf(type));
        }
        List<OrganizationPieceAttribute> orgAttrs = orgAttributes.listAll(orgId);
        for (ValueInput input : inputs) {
            out.add(resolveOne(input, typeAttrs, orgAttrs));
        }
        return out;
    }

    private AttributeValueInputDto resolveOne(ValueInput input, List<PieceTypeAttribute> typeAttrs,
                                              List<OrganizationPieceAttribute> orgAttrs) {
        if (input == null || input.attribute() == null || input.attribute().isBlank()) {
            throw new ToolException(ErrorCodes.MCP_INVALID_ARGUMENTS, null,
                    "Each attribute value needs an `attribute` (name, label or id).", Map.of("detail", "attribute missing"));
        }
        String ref = input.attribute().trim();
        AttributeScope scope = parseScope(input.scope());
        List<PieceTypeAttribute> typeMatches = new ArrayList<>();
        List<OrganizationPieceAttribute> orgMatches = new ArrayList<>();
        if (scope != AttributeScope.ORG) {
            for (PieceTypeAttribute a : typeAttrs) {
                if (matches(ref, a.getId(), a.getName(), a.getDisplayName())) typeMatches.add(a);
            }
        }
        if (scope == AttributeScope.ORG || typeMatches.isEmpty()) {
            for (OrganizationPieceAttribute a : orgAttrs) {
                if (matches(ref, a.getId(), a.getName(), a.getDisplayName())) orgMatches.add(a);
            }
        }
        if (!typeMatches.isEmpty()) {
            // A type attribute wins over an organization attribute with the same name.
            if (distinctIds(typeMatches) > 1) {
                throw ambiguous(ref, candidates(typeMatches, List.of()));
            }
            return new AttributeValueInputDto(typeMatches.get(0).getId(), AttributeScope.TYPE, input.value());
        }
        if (orgMatches.size() == 1) {
            return new AttributeValueInputDto(orgMatches.get(0).getId(), AttributeScope.ORG, input.value());
        }
        if (orgMatches.isEmpty()) {
            throw new ToolException("pieces.attribute_unknown", "Unknown attribute: " + ref,
                    "Use an attribute name, label or id from piece_types_list or org_attributes_list.",
                    Map.of("attribute", ref));
        }
        throw ambiguous(ref, candidates(List.of(), orgMatches));
    }

    private static int distinctIds(List<PieceTypeAttribute> attrs) {
        Set<Integer> ids = new LinkedHashSet<>();
        attrs.forEach(a -> ids.add(a.getId()));
        return ids.size();
    }

    private static List<String> candidates(List<PieceTypeAttribute> typeMatches,
                                           List<OrganizationPieceAttribute> orgMatches) {
        List<String> out = new ArrayList<>();
        for (PieceTypeAttribute a : typeMatches) {
            out.add("TYPE:" + a.getId() + " " + a.getName() + " (" + a.getPieceType().getName() + ")");
        }
        for (OrganizationPieceAttribute a : orgMatches) {
            out.add("ORG:" + a.getId() + " " + a.getName());
        }
        return out;
    }

    private static ToolException ambiguous(String ref, List<String> candidates) {
        return new ToolException("pieces.attribute_ambiguous", "Attribute reference is ambiguous: " + ref,
                "Pass the attribute id and scope (TYPE or ORG) instead of the name.",
                Map.of("attribute", ref, "candidates", candidates));
    }

    static AttributeScope parseScope(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return AttributeScope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException(ErrorCodes.MCP_INVALID_ARGUMENTS, null, "scope must be TYPE or ORG",
                    Map.of("detail", "scope=" + raw));
        }
    }

    static boolean matches(String ref, Integer id, String name, String displayName) {
        if (id != null && ref.equals(String.valueOf(id))) return true;
        return (name != null && name.equalsIgnoreCase(ref)) || (displayName != null && displayName.equalsIgnoreCase(ref));
    }
}

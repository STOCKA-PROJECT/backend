package com.stocka.backend.modules.mcp.tool;

import com.stocka.backend.modules.common.schema.Param;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.dto.CreateOrganizationPieceAttributeDto;
import com.stocka.backend.modules.organizations.dto.OrganizationPieceAttributeResponseDto;
import com.stocka.backend.modules.organizations.dto.UpdateOrganizationPieceAttributeDto;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.organizations.security.OrganizationSecurity;
import com.stocka.backend.modules.organizations.service.OrganizationPieceAttributeService;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.dto.PieceTypeActionResponseDto;
import com.stocka.backend.modules.piecetypes.dto.PieceTypeAttributeResponseDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;
import com.stocka.backend.modules.piecetypes.service.PieceTypeActionService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeAttributeService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.piecetypes.service.ValidatorsJsonCodec;

/**
 * Piece types, their attributes, and organization-wide piece attributes.
 */
@Component
public class PieceTypeTools implements ToolSource {

    public record OrgInput(@Param(value = "Organization slug", required = true) String org) {
    }

    public record TypeInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id", required = true) Integer typeId) {
    }

    /** Validation rules of an attribute; every field optional. */
    public record ValidatorsInput(
            @Param("Allowed options for SELECT / MULTI_SELECT") List<String> options,
            @Param("Minimum value for INTEGER / DECIMAL / PRICE") BigDecimal min,
            @Param("Maximum value for INTEGER / DECIMAL / PRICE") BigDecimal max,
            @Param("Minimum text length") Integer minLength,
            @Param("Maximum text length") Integer maxLength,
            @Param("Regular expression the text must match") String regex,
            @Param("Decimal places for DECIMAL / PRICE") Integer decimals,
            @Param("Currency code for PRICE, e.g. EUR") String currency,
            @Param("Minimum items for MULTI_SELECT") Integer minItems,
            @Param("Maximum items for MULTI_SELECT") Integer maxItems) {
        AttributeValidatorsDto toDto() {
            AttributeValidatorsDto dto = new AttributeValidatorsDto();
            dto.setOptions(options);
            dto.setMin(min);
            dto.setMax(max);
            dto.setMinLength(minLength);
            dto.setMaxLength(maxLength);
            dto.setRegex(regex);
            dto.setDecimals(decimals);
            dto.setCurrency(currency);
            dto.setMinItems(minItems);
            dto.setMaxItems(maxItems);
            return dto;
        }
    }

    public record AttributeInput(
            @Param(value = "Technical name (lowercase, no spaces), unique within the type", required = true) String name,
            @Param(value = "Label shown to people", required = true) String displayName,
            @Param(value = "TEXT, LONGTEXT, INTEGER, DECIMAL, PRICE, DATE, DATETIME, BOOLEAN, SELECT, MULTI_SELECT, URL, EMAIL or MEMBER", required = true) AttributeType type,
            @Param("Whether a value is required for the piece to be ACTIVE (default false)") Boolean required,
            @Param("Order among the type's attributes") Integer position,
            @Param("Validation rules") ValidatorsInput validators) {
        CreatePieceTypeAttributeDto toCreate() {
            CreatePieceTypeAttributeDto dto = new CreatePieceTypeAttributeDto();
            dto.setName(name);
            dto.setDisplayName(displayName);
            dto.setType(type);
            dto.setRequired(required);
            dto.setPosition(position);
            dto.setValidators(validators == null ? null : validators.toDto());
            return dto;
        }
    }

    public record CreateTypeInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Type name, unique in the organization", required = true) String name,
            @Param("Hardware output kind (relay, led_strip, led_segment, motor, audio, moving_head) when pieces of this type are driven by the player; omit for plain inventory") String kind,
            @Param("Initial attributes") List<AttributeInput> attributes) {
    }

    public record UpdateTypeInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id", required = true) Integer typeId,
            @Param("New name") String name,
            @Param("New hardware kind (cannot be removed once set)") String kind) {
    }

    public record AddAttributeInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id", required = true) Integer typeId,
            @Param(value = "The attribute", required = true) AttributeInput attribute) {
    }

    public record UpdateAttributeInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id", required = true) Integer typeId,
            @Param(value = "Attribute id", required = true) Integer attributeId,
            @Param("New technical name") String name,
            @Param("New label") String displayName,
            @Param("New type") AttributeType type,
            @Param("Whether required") Boolean required,
            @Param("New position") Integer position,
            @Param("New validation rules (replaces the current ones)") ValidatorsInput validators) {
    }

    public record AttributeRefInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id", required = true) Integer typeId,
            @Param(value = "Attribute id", required = true) Integer attributeId) {
    }

    public record OrgAttributeCreateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "The attribute", required = true) AttributeInput attribute) {
    }

    public record OrgAttributeUpdateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Attribute id", required = true) Integer attributeId,
            @Param("New technical name") String name,
            @Param("New label") String displayName,
            @Param("New type") AttributeType type,
            @Param("Whether required") Boolean required,
            @Param("New position") Integer position,
            @Param("New validation rules (replaces the current ones)") ValidatorsInput validators) {
    }

    public record OrgAttributeRefInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Attribute id", required = true) Integer attributeId) {
    }

    public record TypeView(
            @Param("Type id") Integer id,
            @Param("Name") String name,
            @Param("Hardware kind or null") String kind,
            @Param("Attributes") List<PieceTypeAttributeResponseDto> attributes,
            @Param("Actions the type exposes to timelines (only when hardware is enabled)") List<PieceTypeActionResponseDto> actions) {
    }

    public record TypesResult(@Param("Piece types") List<TypeView> items) {
    }

    public record OrgAttributesResult(@Param("Organization-wide attributes") List<OrganizationPieceAttributeResponseDto> items) {
    }

    private final OrgAccess access;
    private final OrganizationSecurity security;
    private final PieceTypeService types;
    private final PieceTypeAttributeService attributes;
    private final PieceTypeActionService actions;
    private final OrganizationPieceAttributeService orgAttributes;
    private final ValidatorsJsonCodec validators;

    public PieceTypeTools(OrgAccess access, OrganizationSecurity security, PieceTypeService types,
                          PieceTypeAttributeService attributes, PieceTypeActionService actions,
                          OrganizationPieceAttributeService orgAttributes, ValidatorsJsonCodec validators) {
        this.access = access;
        this.security = security;
        this.types = types;
        this.attributes = attributes;
        this.actions = actions;
        this.orgAttributes = orgAttributes;
        this.validators = validators;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("piece_types_list",
                        "Piece types of the organization with their attributes (name, label, type, required, "
                                + "validation rules) and, when hardware is enabled, their actions. Needed before "
                                + "creating pieces or clips.",
                        OrgInput.class, OAuthScope.READ, ToolHints.READ, this::list).returning(TypesResult.class),
                ToolDefinition.of("piece_type_get",
                        "One piece type with attributes and actions.",
                        TypeInput.class, OAuthScope.READ, ToolHints.READ, this::get).returning(TypeView.class),
                ToolDefinition.of("piece_type_create",
                        "Creates a piece type with optional initial attributes. Needs OWNER or MANAGER.",
                        CreateTypeInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.CREATE, this::create)
                        .returning(TypeView.class),
                ToolDefinition.of("piece_type_update",
                        "Renames a piece type or sets its hardware kind. Needs OWNER or MANAGER.",
                        UpdateTypeInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.UPDATE, this::update)
                        .returning(TypeView.class),
                ToolDefinition.of("piece_type_delete",
                        "Deletes a piece type that no piece uses. Needs OWNER or MANAGER and the delete scope.",
                        TypeInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::delete)
                        .returning(ToolResults.Done.class),
                ToolDefinition.of("piece_type_attribute_add",
                        "Adds an attribute to a piece type. Pieces of the type become PENDING if the attribute is "
                                + "required until they get a value. Needs OWNER or MANAGER.",
                        AddAttributeInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.CREATE, this::addAttribute)
                        .returning(PieceTypeAttributeResponseDto.class),
                ToolDefinition.of("piece_type_attribute_update",
                        "Changes an attribute of a piece type (name, label, type, required, position, rules). "
                                + "Needs OWNER or MANAGER.",
                        UpdateAttributeInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.UPDATE, this::updateAttribute)
                        .returning(PieceTypeAttributeResponseDto.class),
                ToolDefinition.of("piece_type_attribute_remove",
                        "Removes an attribute from a piece type and its values from every piece. Needs OWNER or "
                                + "MANAGER and the delete scope.",
                        AttributeRefInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::removeAttribute)
                        .returning(ToolResults.Done.class),
                ToolDefinition.of("org_attributes_list",
                        "Organization-wide piece attributes (apply to every piece regardless of type).",
                        OrgInput.class, OAuthScope.READ, ToolHints.READ, this::listOrgAttributes)
                        .returning(OrgAttributesResult.class),
                ToolDefinition.of("org_attribute_create",
                        "Creates an organization-wide piece attribute. Needs OWNER or MANAGER.",
                        OrgAttributeCreateInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.CREATE, this::createOrgAttribute)
                        .returning(OrganizationPieceAttributeResponseDto.class),
                ToolDefinition.of("org_attribute_update",
                        "Changes an organization-wide piece attribute. Needs OWNER or MANAGER.",
                        OrgAttributeUpdateInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.UPDATE, this::updateOrgAttribute)
                        .returning(OrganizationPieceAttributeResponseDto.class),
                ToolDefinition.of("org_attribute_delete",
                        "Deletes an organization-wide piece attribute and its values. Needs OWNER or MANAGER and "
                                + "the delete scope.",
                        OrgAttributeRefInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::deleteOrgAttribute)
                        .returning(ToolResults.Done.class));
    }

    // ---------- types ----------

    private TypesResult list(ToolCall<OrgInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        boolean withActions = security.canReadPieceTypeActions(org.getSlug(), call.caller().user());
        List<TypeView> out = new ArrayList<>();
        for (PieceType type : types.listAll(org.getId())) {
            out.add(view(type, withActions));
        }
        return new TypesResult(out);
    }

    private TypeView get(ToolCall<TypeInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        PieceType type = types.findInOrg(org.getId(), call.input().typeId());
        return view(type, security.canReadPieceTypeActions(org.getSlug(), call.caller().user()));
    }

    private TypeView create(ToolCall<CreateTypeInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        CreatePieceTypeDto dto = new CreatePieceTypeDto();
        dto.setName(call.input().name());
        dto.setKind(call.input().kind());
        if (call.input().attributes() != null) {
            List<CreatePieceTypeAttributeDto> attrs = new ArrayList<>();
            for (AttributeInput a : call.input().attributes()) {
                attrs.add(a.toCreate());
            }
            dto.setAttributes(attrs);
        }
        return view(types.create(org.getId(), dto), false);
    }

    private TypeView update(ToolCall<UpdateTypeInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        UpdatePieceTypeDto dto = new UpdatePieceTypeDto();
        dto.setName(call.input().name());
        dto.setKind(call.input().kind());
        return view(types.update(org.getId(), call.input().typeId(), dto), false);
    }

    private ToolResults.Done delete(ToolCall<TypeInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        types.softDelete(org.getId(), call.input().typeId());
        return ToolResults.Done.of("Piece type " + call.input().typeId() + " deleted");
    }

    // ---------- type attributes ----------

    private PieceTypeAttributeResponseDto addAttribute(ToolCall<AddAttributeInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        PieceTypeAttribute attr = types.addAttribute(org.getId(), call.input().typeId(), call.input().attribute().toCreate());
        return attributeView(attr);
    }

    private PieceTypeAttributeResponseDto updateAttribute(ToolCall<UpdateAttributeInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        UpdatePieceTypeAttributeDto dto = new UpdatePieceTypeAttributeDto();
        dto.setName(call.input().name());
        dto.setDisplayName(call.input().displayName());
        dto.setType(call.input().type());
        dto.setRequired(call.input().required());
        dto.setPosition(call.input().position());
        dto.setValidators(call.input().validators() == null ? null : call.input().validators().toDto());
        return attributeView(attributes.update(org.getId(), call.input().typeId(), call.input().attributeId(), dto));
    }

    private ToolResults.Done removeAttribute(ToolCall<AttributeRefInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        attributes.softDelete(org.getId(), call.input().typeId(), call.input().attributeId());
        return ToolResults.Done.of("Attribute " + call.input().attributeId() + " removed");
    }

    // ---------- organization attributes ----------

    private OrgAttributesResult listOrgAttributes(ToolCall<OrgInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        List<OrganizationPieceAttributeResponseDto> out = new ArrayList<>();
        for (OrganizationPieceAttribute attr : orgAttributes.listAll(org.getId())) {
            out.add(orgAttributeView(attr));
        }
        return new OrgAttributesResult(out);
    }

    private OrganizationPieceAttributeResponseDto createOrgAttribute(ToolCall<OrgAttributeCreateInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        AttributeInput a = call.input().attribute();
        CreateOrganizationPieceAttributeDto dto = new CreateOrganizationPieceAttributeDto();
        dto.setName(a.name());
        dto.setDisplayName(a.displayName());
        dto.setType(a.type());
        dto.setRequired(a.required());
        dto.setPosition(a.position());
        dto.setValidators(a.validators() == null ? null : a.validators().toDto());
        return orgAttributeView(orgAttributes.create(org.getId(), dto));
    }

    private OrganizationPieceAttributeResponseDto updateOrgAttribute(ToolCall<OrgAttributeUpdateInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        UpdateOrganizationPieceAttributeDto dto = new UpdateOrganizationPieceAttributeDto();
        dto.setName(call.input().name());
        dto.setDisplayName(call.input().displayName());
        dto.setType(call.input().type());
        dto.setRequired(call.input().required());
        dto.setPosition(call.input().position());
        dto.setValidators(call.input().validators() == null ? null : call.input().validators().toDto());
        return orgAttributeView(orgAttributes.update(org.getId(), call.input().attributeId(), dto));
    }

    private ToolResults.Done deleteOrgAttribute(ToolCall<OrgAttributeRefInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        orgAttributes.softDelete(org.getId(), call.input().attributeId());
        return ToolResults.Done.of("Organization attribute " + call.input().attributeId() + " deleted");
    }

    // ---------- views ----------

    TypeView view(PieceType type, boolean withActions) {
        List<PieceTypeAttributeResponseDto> attrs = new ArrayList<>();
        for (PieceTypeAttribute attr : types.attributesOf(type)) {
            attrs.add(attributeView(attr));
        }
        List<PieceTypeActionResponseDto> actionViews = null;
        if (withActions) {
            actionViews = new ArrayList<>();
            for (PieceTypeAction action : actions.listOf(type)) {
                actionViews.add(PieceTypeActionResponseDto.from(action, actions.parametersOf(action)));
            }
        }
        return new TypeView(type.getId(), type.getName(), type.getKind() == null ? null : type.getKind().catalogId(),
                attrs, actionViews);
    }

    private PieceTypeAttributeResponseDto attributeView(PieceTypeAttribute attr) {
        return PieceTypeAttributeResponseDto.from(attr, validators.deserialize(attr.getValidatorsJson()));
    }

    private OrganizationPieceAttributeResponseDto orgAttributeView(OrganizationPieceAttribute attr) {
        return OrganizationPieceAttributeResponseDto.from(attr, validators.deserialize(attr.getValidatorsJson()));
    }

    static Map<String, Object> none() {
        return Map.of();
    }
}

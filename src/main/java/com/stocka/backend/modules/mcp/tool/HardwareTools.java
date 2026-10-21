package com.stocka.backend.modules.mcp.tool;

import com.stocka.backend.modules.common.schema.Param;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeActionDto;
import com.stocka.backend.modules.piecetypes.dto.PieceTypeActionResponseDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeActionDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;
import com.stocka.backend.modules.piecetypes.service.PieceTypeActionService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.ports.dto.CreatePortDto;
import com.stocka.backend.modules.ports.dto.PortResponseDto;
import com.stocka.backend.modules.ports.dto.UpdatePortDto;
import com.stocka.backend.modules.ports.entity.Port;
import com.stocka.backend.modules.ports.service.PortService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Hardware (private feature of administrator-owned organizations): the output catalog, GPIO ports
 * and the actions piece types expose to timelines.
 */
@Component
public class HardwareTools implements ToolSource {

    public record OrgInput(@Param(value = "Organization slug", required = true) String org) {
    }

    public record NoInput() {
    }

    public record PortsResult(@Param("Ports") List<PortResponseDto> items) {
    }

    /** Parameter of a port or of a type action. */
    public record ParameterInput(
            @Param(value = "Technical name, as in the catalog action", required = true) String name,
            @Param("Label") String displayName,
            @Param(value = "INTEGER, DECIMAL, BOOLEAN, TEXT, SELECT …", required = true) AttributeType type,
            @Param("Whether a value is required") Boolean required,
            @Param("Order") Integer position,
            @Param("Allowed options for SELECT") List<String> options,
            @Param("Minimum") BigDecimal min,
            @Param("Maximum") BigDecimal max,
            @Param("true = the clip sets the value; false = fixed by staticValue") Boolean dynamic,
            @Param("Fixed value when not dynamic") String staticValue,
            @Param("true when this parameter is the clip duration (seconds)") Boolean isDuration) {
        ActionParameterDto toDto() {
            ActionParameterDto dto = new ActionParameterDto();
            dto.setName(name);
            dto.setDisplayName(displayName);
            dto.setType(type);
            dto.setRequired(required);
            dto.setPosition(position);
            if (options != null || min != null || max != null) {
                AttributeValidatorsDto v = new AttributeValidatorsDto();
                v.setOptions(options);
                v.setMin(min);
                v.setMax(max);
                dto.setValidators(v);
            }
            dto.setDynamic(dynamic);
            dto.setStaticValue(staticValue);
            dto.setIsDuration(isDuration);
            return dto;
        }
    }

    public record CreatePortInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Port name, unique in the organization", required = true) String name,
            @Param(value = "Kind from the catalog: relay, gpio_expander, led_strip, led_segment, motor, audio, moving_head, sensor", required = true) String kind,
            @Param("Piece type id whose kind matches (omit for infrastructure kinds gpio_expander, led_strip and sensor)") Integer pieceTypeId,
            @Param("BCM GPIO pin for kinds that take one (led_strip; relay and sensor unless they use expander + channel)") Integer pin,
            @Param("Kind-specific configuration as in the catalog (e.g. relay: expander + channel 0-15 instead of pin, activeLow; gpio_expander: chip mcp23017, bus, address 32-39, activeLow; led_strip: dma, channel, length; led_segment: strip, first, last; motor: pinForward + pinReverse or expander + channelForward + channelReverse, maxTimeForward, maxTimeReverse, optional limitForward/limitReverse naming sensor ports, lockOnTimeout, switchDelayMs; sensor: expander + channel instead of pin, activeLow, pull up/down/none, debounceMs, onActive none/play/stop_show/panic; moving_head: profile, address 1-512)") Map<String, Object> config) {
    }

    public record UpdatePortInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Port id", required = true) Integer portId,
            @Param("New name") String name,
            @Param("New piece type id") Integer pieceTypeId,
            @Param("New pin") Integer pin,
            @Param("New configuration (replaces the current one)") Map<String, Object> config) {
    }

    public record PortInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Port id", required = true) Integer portId) {
    }

    public record TypeInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id", required = true) Integer typeId) {
    }

    public record ActionsResult(@Param("Actions") List<PieceTypeActionResponseDto> items) {
    }

    public record CreateActionInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id (the type must have a kind)", required = true) Integer typeId,
            @Param(value = "Action name from the catalog for the type's kind (e.g. turn_on, animate, fill, play)", required = true) String name,
            @Param("Label") String displayName,
            @Param("Description") String description,
            @Param("Order") Integer position,
            @Param("Parameters: every catalog parameter you expose, each either dynamic (set per clip) or static") List<ParameterInput> parameters) {
    }

    public record UpdateActionInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id", required = true) Integer typeId,
            @Param(value = "Action id", required = true) Integer actionId,
            @Param("New name") String name,
            @Param("New label") String displayName,
            @Param("New description") String description,
            @Param("New order") Integer position,
            @Param("New parameters (replace the current ones)") List<ParameterInput> parameters) {
    }

    public record ActionInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Piece type id", required = true) Integer typeId,
            @Param(value = "Action id", required = true) Integer actionId) {
    }

    private final OrgAccess access;
    private final OutputCatalog catalog;
    private final PortService ports;
    private final PieceTypeService types;
    private final PieceTypeActionService actions;
    private final JsonMapper json;

    public HardwareTools(OrgAccess access, OutputCatalog catalog, PortService ports, PieceTypeService types,
                         PieceTypeActionService actions, JsonMapper json) {
        this.access = access;
        this.catalog = catalog;
        this.ports = ports;
        this.types = types;
        this.actions = actions;
        this.json = json;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("catalog_get",
                        "The output catalog: hardware kinds, their configuration fields, actions with parameters "
                                + "(types, ranges, enums, which one is the duration) and the Raspberry Pi board limits. "
                                + "Also available as the stocka://catalog resource.",
                        NoInput.class, OAuthScope.READ, ToolHints.READ, this::catalogGet),
                ToolDefinition.of("ports_list",
                        "GPIO ports of the organization (kind, pin, configuration, bound piece type).",
                        OrgInput.class, OAuthScope.READ, ToolHints.READ, this::listPorts).returning(PortsResult.class),
                ToolDefinition.of("port_create",
                        "Creates a port validated against the catalog and the board (pins, DMA, strip ranges). "
                                + "Needs OWNER or MANAGER of a hardware-enabled organization.",
                        CreatePortInput.class, OAuthScope.HARDWARE_WRITE, ToolHints.CREATE, this::createPort)
                        .returning(PortResponseDto.class),
                ToolDefinition.of("port_update",
                        "Changes a port (name, piece type, pin, configuration; the kind is immutable).",
                        UpdatePortInput.class, OAuthScope.HARDWARE_WRITE, ToolHints.UPDATE, this::updatePort)
                        .returning(PortResponseDto.class),
                ToolDefinition.of("port_delete",
                        "Deletes a port (a strip with segments cannot be deleted). Needs the delete scope.",
                        PortInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::deletePort)
                        .returning(ToolResults.Done.class),
                ToolDefinition.of("piece_type_actions_list",
                        "Actions a piece type exposes to timelines, with their parameters.",
                        TypeInput.class, OAuthScope.READ, ToolHints.READ, this::listActions).returning(ActionsResult.class),
                ToolDefinition.of("piece_type_action_create",
                        "Adds an action to a piece type, validated against the catalog of the type's kind. "
                                + "Needs OWNER or MANAGER of a hardware-enabled organization.",
                        CreateActionInput.class, OAuthScope.HARDWARE_WRITE, ToolHints.CREATE, this::createAction)
                        .returning(PieceTypeActionResponseDto.class),
                ToolDefinition.of("piece_type_action_update",
                        "Changes an action of a piece type.",
                        UpdateActionInput.class, OAuthScope.HARDWARE_WRITE, ToolHints.UPDATE, this::updateAction)
                        .returning(PieceTypeActionResponseDto.class),
                ToolDefinition.of("piece_type_action_delete",
                        "Deletes an action of a piece type (clips using it become invalid). Needs the delete scope.",
                        ActionInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::deleteAction)
                        .returning(ToolResults.Done.class));
    }

    private Object catalogGet(ToolCall<NoInput> call) {
        return json.convertValue(catalog.raw(), Map.class);
    }

    private PortsResult listPorts(ToolCall<OrgInput> call) {
        Organization org = access.readHardware(call.caller(), call.input().org());
        List<PortResponseDto> out = new ArrayList<>();
        for (Port port : ports.listAll(org.getId())) {
            out.add(portView(port));
        }
        return new PortsResult(out);
    }

    private PortResponseDto createPort(ToolCall<CreatePortInput> call) {
        CreatePortInput in = call.input();
        Organization org = access.manageHardware(call.caller(), in.org());
        CreatePortDto dto = new CreatePortDto();
        dto.setName(in.name());
        dto.setKind(in.kind());
        dto.setPieceTypeId(in.pieceTypeId());
        dto.setPin(in.pin());
        dto.setConfig(in.config());
        return portView(ports.create(org.getId(), dto));
    }

    private PortResponseDto updatePort(ToolCall<UpdatePortInput> call) {
        UpdatePortInput in = call.input();
        Organization org = access.manageHardware(call.caller(), in.org());
        UpdatePortDto dto = new UpdatePortDto();
        dto.setName(in.name());
        dto.setPieceTypeId(in.pieceTypeId());
        dto.setPin(in.pin());
        dto.setConfig(in.config());
        return portView(ports.update(org.getId(), in.portId(), dto));
    }

    private ToolResults.Done deletePort(ToolCall<PortInput> call) {
        Organization org = access.manageHardware(call.caller(), call.input().org());
        ports.softDelete(org.getId(), call.input().portId());
        return ToolResults.Done.of("Port " + call.input().portId() + " deleted");
    }

    private ActionsResult listActions(ToolCall<TypeInput> call) {
        Organization org = access.readHardware(call.caller(), call.input().org());
        PieceType type = types.findInOrg(org.getId(), call.input().typeId());
        List<PieceTypeActionResponseDto> out = new ArrayList<>();
        for (PieceTypeAction action : actions.listOf(type)) {
            out.add(PieceTypeActionResponseDto.from(action, actions.parametersOf(action)));
        }
        return new ActionsResult(out);
    }

    private PieceTypeActionResponseDto createAction(ToolCall<CreateActionInput> call) {
        CreateActionInput in = call.input();
        Organization org = access.manageHardware(call.caller(), in.org());
        CreatePieceTypeActionDto dto = new CreatePieceTypeActionDto();
        dto.setName(in.name());
        dto.setDisplayName(in.displayName());
        dto.setDescription(in.description());
        dto.setPosition(in.position());
        dto.setParameters(parameters(in.parameters()));
        PieceTypeAction action = actions.create(org.getId(), in.typeId(), dto);
        return PieceTypeActionResponseDto.from(action, actions.parametersOf(action));
    }

    private PieceTypeActionResponseDto updateAction(ToolCall<UpdateActionInput> call) {
        UpdateActionInput in = call.input();
        Organization org = access.manageHardware(call.caller(), in.org());
        UpdatePieceTypeActionDto dto = new UpdatePieceTypeActionDto();
        dto.setName(in.name());
        dto.setDisplayName(in.displayName());
        dto.setDescription(in.description());
        dto.setPosition(in.position());
        dto.setParameters(in.parameters() == null ? null : parameters(in.parameters()));
        PieceTypeAction action = actions.update(org.getId(), in.typeId(), in.actionId(), dto);
        return PieceTypeActionResponseDto.from(action, actions.parametersOf(action));
    }

    private ToolResults.Done deleteAction(ToolCall<ActionInput> call) {
        Organization org = access.manageHardware(call.caller(), call.input().org());
        actions.softDelete(org.getId(), call.input().typeId(), call.input().actionId());
        return ToolResults.Done.of("Action " + call.input().actionId() + " deleted");
    }

    private static List<ActionParameterDto> parameters(List<ParameterInput> inputs) {
        List<ActionParameterDto> out = new ArrayList<>();
        if (inputs != null) {
            for (ParameterInput p : inputs) {
                out.add(p.toDto());
            }
        }
        return out;
    }

    private PortResponseDto portView(Port port) {
        return PortResponseDto.from(port, ports.configOf(port), ports.parametersOf(port),
                ports.pieceTypeNameOf(port.getPieceTypeId()));
    }
}

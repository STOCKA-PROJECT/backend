package com.stocka.backend.modules.piecetypes.dto;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * A single typed parameter of a {@code PieceTypeAction}, e.g. {@code tiempo} of type
 * {@link AttributeType#INTEGER}. Reuses the attribute {@link AttributeType} and
 * {@link AttributeValidatorsDto} so parameters share the same typing and validation machinery as
 * piece-type attributes. Used both for inbound payloads and for serialization into the action's
 * {@code parameters_json} blob.
 *
 * <p>Each parameter declares a <em>binding mode</em> through {@link #dynamic}:
 * <ul>
 *   <li><b>static</b> ({@code dynamic == false}, the default): the value is fixed once at definition
 *       time in {@link #staticValue} and shared by every piece of the type, in every timeline.</li>
 *   <li><b>dynamic</b> ({@code dynamic == true}): no value is stored here; it is supplied per clip in
 *       the timeline editor, so {@link #staticValue} is irrelevant and kept {@code null}.</li>
 * </ul>
 * The static value is stored in the same canonical string form used for piece attribute values
 * (e.g. {@code "true"} for BOOLEAN, a JSON array for MULTI_SELECT), so the same UI controls can edit
 * it.
 *
 * <p>A single numeric ({@code INTEGER}/{@code DECIMAL}) parameter per action may be flagged as the
 * <b>duration</b> via {@link #isDuration}: its value (in seconds) is the length of the clip on the
 * timeline, so there is a single source of time. A duration parameter still uses the binding above
 * (static = fixed length for every clip; dynamic = length set per clip).
 */
public class ActionParameterDto {
    private String name;
    private String displayName;
    private AttributeType type;
    private Boolean required;
    private Integer position;
    private AttributeValidatorsDto validators;
    private Boolean dynamic;
    private String staticValue;
    private Boolean isDuration;

    public String getName() { return name; }
    public ActionParameterDto setName(String v) { this.name = v; return this; }

    public String getDisplayName() { return displayName; }
    public ActionParameterDto setDisplayName(String v) { this.displayName = v; return this; }

    public AttributeType getType() { return type; }
    public ActionParameterDto setType(AttributeType v) { this.type = v; return this; }

    public Boolean getRequired() { return required; }
    public ActionParameterDto setRequired(Boolean v) { this.required = v; return this; }

    public Integer getPosition() { return position; }
    public ActionParameterDto setPosition(Integer v) { this.position = v; return this; }

    public AttributeValidatorsDto getValidators() { return validators; }
    public ActionParameterDto setValidators(AttributeValidatorsDto v) { this.validators = v; return this; }

    public Boolean getDynamic() { return dynamic; }
    public ActionParameterDto setDynamic(Boolean v) { this.dynamic = v; return this; }

    public String getStaticValue() { return staticValue; }
    public ActionParameterDto setStaticValue(String v) { this.staticValue = v; return this; }

    public Boolean getIsDuration() { return isDuration; }
    public ActionParameterDto setIsDuration(Boolean v) { this.isDuration = v; return this; }
}

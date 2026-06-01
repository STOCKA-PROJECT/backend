package com.stocka.backend.modules.piecetypes.dto;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * A single typed parameter of a {@code PieceTypeAction}, e.g. {@code tiempo} of type
 * {@link AttributeType#INTEGER}. Reuses the attribute {@link AttributeType} and
 * {@link AttributeValidatorsDto} so parameters share the same typing and validation machinery as
 * piece-type attributes. Used both for inbound payloads and for serialization into the action's
 * {@code parameters_json} blob.
 */
public class ActionParameterDto {
    private String name;
    private String displayName;
    private AttributeType type;
    private Boolean required;
    private Integer position;
    private AttributeValidatorsDto validators;

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
}

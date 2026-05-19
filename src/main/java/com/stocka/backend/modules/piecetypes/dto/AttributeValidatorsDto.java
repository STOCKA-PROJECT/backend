package com.stocka.backend.modules.piecetypes.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * All validation rules a {@code PieceTypeAttribute} can carry. Every field is optional; the
 * relevant subset depends on the attribute {@code type}:
 *
 * <ul>
 *   <li>TEXT / LONGTEXT: {@code minLength}, {@code maxLength}, {@code regex}.</li>
 *   <li>INTEGER: {@code min}, {@code max} (parsed as {@code Long}).</li>
 *   <li>DECIMAL / PRICE: {@code min}, {@code max}, {@code decimals}, {@code currency} (PRICE only).</li>
 *   <li>DATE / DATETIME: {@code minDate}, {@code maxDate}, {@code allowFuture}, {@code allowPast}.</li>
 *   <li>SELECT / MULTI_SELECT: {@code options}; MULTI_SELECT also uses {@code minItems}/{@code maxItems}.</li>
 *   <li>URL / EMAIL: {@code maxLength}.</li>
 *   <li>MEMBER: {@code eligibleRoles} restricts which organization roles may be selected; when
 *       absent or empty any active member is allowed.</li>
 * </ul>
 *
 * Unknown fields are ignored. Defaults are applied by each validator if not provided.
 */
public class AttributeValidatorsDto {
    private Integer minLength;
    private Integer maxLength;
    private String regex;
    private BigDecimal min;
    private BigDecimal max;
    private Integer decimals;
    private String currency;
    private String minDate;
    private String maxDate;
    private Boolean allowFuture;
    private Boolean allowPast;
    private List<String> options;
    private Integer minItems;
    private Integer maxItems;
    private List<String> eligibleRoles;

    public Integer getMinLength() { return minLength; }
    public AttributeValidatorsDto setMinLength(Integer v) { this.minLength = v; return this; }

    public Integer getMaxLength() { return maxLength; }
    public AttributeValidatorsDto setMaxLength(Integer v) { this.maxLength = v; return this; }

    public String getRegex() { return regex; }
    public AttributeValidatorsDto setRegex(String v) { this.regex = v; return this; }

    public BigDecimal getMin() { return min; }
    public AttributeValidatorsDto setMin(BigDecimal v) { this.min = v; return this; }

    public BigDecimal getMax() { return max; }
    public AttributeValidatorsDto setMax(BigDecimal v) { this.max = v; return this; }

    public Integer getDecimals() { return decimals; }
    public AttributeValidatorsDto setDecimals(Integer v) { this.decimals = v; return this; }

    public String getCurrency() { return currency; }
    public AttributeValidatorsDto setCurrency(String v) { this.currency = v; return this; }

    public String getMinDate() { return minDate; }
    public AttributeValidatorsDto setMinDate(String v) { this.minDate = v; return this; }

    public String getMaxDate() { return maxDate; }
    public AttributeValidatorsDto setMaxDate(String v) { this.maxDate = v; return this; }

    public Boolean getAllowFuture() { return allowFuture; }
    public AttributeValidatorsDto setAllowFuture(Boolean v) { this.allowFuture = v; return this; }

    public Boolean getAllowPast() { return allowPast; }
    public AttributeValidatorsDto setAllowPast(Boolean v) { this.allowPast = v; return this; }

    public List<String> getOptions() { return options; }
    public AttributeValidatorsDto setOptions(List<String> v) { this.options = v; return this; }

    public Integer getMinItems() { return minItems; }
    public AttributeValidatorsDto setMinItems(Integer v) { this.minItems = v; return this; }

    public Integer getMaxItems() { return maxItems; }
    public AttributeValidatorsDto setMaxItems(Integer v) { this.maxItems = v; return this; }

    public List<String> getEligibleRoles() { return eligibleRoles; }
    public AttributeValidatorsDto setEligibleRoles(List<String> v) { this.eligibleRoles = v; return this; }
}

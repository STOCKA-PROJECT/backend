package com.stocka.backend.modules.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One action a kind of output can execute, as the Raspberry Pi controller implements it.
 *
 * @param name        method name on the controller ({@code turn_on}, {@code animate}, ...)
 * @param params      common parameters by name
 * @param animations  for {@code animate}: the animations and their extra parameters; otherwise empty
 * @param unsupported controller parameters the contract deliberately does not expose
 * @param continuous  whether the action keeps running until replaced or until its duration ends
 */
public record CatalogAction(
        String name,
        Map<String, CatalogParam> params,
        Map<String, CatalogAnimation> animations,
        List<String> unsupported,
        boolean continuous
) {
    /**
     * Every parameter this action may receive: the common ones plus the union of all animations.
     *
     * @return ordered map by parameter name
     */
    public Map<String, CatalogParam> allParams() {
        Map<String, CatalogParam> out = new LinkedHashMap<>(params);
        animations.values().forEach(a -> out.putAll(a.params()));
        return out;
    }

    /**
     * Parameters allowed when the animation is fixed: the common ones plus that animation's.
     *
     * @param animation animation id; unknown ids yield only the common parameters
     * @return ordered map by parameter name
     */
    public Map<String, CatalogParam> paramsFor(String animation) {
        Map<String, CatalogParam> out = new LinkedHashMap<>(params);
        CatalogAnimation a = animation == null ? null : animations.get(animation);
        if (a != null) {
            out.putAll(a.params());
        }
        return out;
    }

    /**
     * Name of the parameter that carries the clip length, if the action has one.
     *
     * @return the duration parameter name
     */
    public Optional<String> durationParam() {
        return params.values().stream().filter(CatalogParam::duration).map(CatalogParam::name).findFirst();
    }

    /**
     * Whether {@code animation} is a known animation of this action.
     *
     * @param animation animation id
     * @return {@code true} when known
     */
    public boolean hasAnimation(String animation) {
        return animation != null && animations.containsKey(animation);
    }
}

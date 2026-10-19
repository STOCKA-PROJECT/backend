package com.stocka.backend.modules.timelines.show;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;

/**
 * Builds the {@code hardware} section of a show package from the organization's ports: one entry
 * per port, keyed by name, with its kind, its pin (for kinds that have one) and its config keys. Pin
 * and board rules are checked later by {@link ShowValidator}. Port of {@code utils/showHardware.ts}.
 */
public final class ShowHardware {

    private ShowHardware() {
    }

    /**
     * The hardware section and the problems met while building it.
     *
     * @param hardware entries by port name, in port order
     * @param issues   unknown kinds
     */
    public record Built(Map<String, Map<String, Object>> hardware, List<ShowIssue> issues) {
    }

    /**
     * @param ports   every port of the organization, assigned or not
     * @param catalog output catalog
     * @return the hardware entries
     */
    public static Built build(List<ShowPort> ports, OutputCatalog catalog) {
        Map<String, Map<String, Object>> hardware = new LinkedHashMap<>();
        List<ShowIssue> issues = new ArrayList<>();
        for (ShowPort port : ports) {
            Optional<OutputKind> kind = OutputKind.parse(port.kind());
            if (kind.isEmpty()) {
                issues.add(ShowIssue.error("E_KIND_UNKNOWN", "El port «" + port.name()
                        + "» té un tipus de sortida desconegut (" + port.kind() + ").", null, port.name()));
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", kind.get().catalogId());
            if (catalog.hasPin(kind.get()) && port.pin() != null) {
                entry.put("pin", port.pin());
            }
            for (Map.Entry<String, Object> e : port.config().entrySet()) {
                if ("pin".equals(e.getKey()) || "kind".equals(e.getKey()) || e.getValue() == null) {
                    continue;
                }
                entry.put(e.getKey(), e.getValue());
            }
            hardware.put(port.name(), entry);
        }
        return new Built(hardware, issues);
    }
}

package com.stocka.backend.modules.common.error;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Construye {@link ProblemDetail} consistentes con el contrato de errores
 * del API: RFC 7807 extendido con los campos {@code code}, {@code params},
 * {@code timestamp} y, opcionalmente, {@code errors}.
 *
 * <p>Resuelve el {@code detail} a partir del {@link MessageSource} con esta
 * cascada de fallbacks:
 * <ol>
 *   <li>{@code errors.<code>} en el locale actual ({@link LocaleContextHolder}).</li>
 *   <li>{@code errors.generic.<status>} (p.ej. {@code errors.generic.400}).</li>
 *   <li>el code literal como último recurso.</li>
 * </ol>
 *
 * <p>Los {@code params} viajan al frontend en el cuerpo del error para que
 * vue-i18n pueda interpolar placeholders nombrados (p.ej. {@code {max}}). En
 * los bundles del backend, en cambio, se usan posicionales ({@code {0}})
 * porque {@link MessageSource} se apoya en {@link java.text.MessageFormat}.
 */
@Component
public class ProblemDetailFactory {

    private final MessageSource messageSource;

    public ProblemDetailFactory(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Construye un {@link ProblemDetail} resolviendo el {@code detail} desde
     * el {@link MessageSource}.
     *
     * @param status HTTP status
     * @param code identificador estable del error
     * @param params parámetros nombrados; pueden ser {@code null}
     * @param instancePath URI del request (campo {@code instance}); puede ser {@code null}
     * @return el {@link ProblemDetail} listo para serializar
     */
    public ProblemDetail build(HttpStatus status, String code, Map<String, Object> params, String instancePath) {
        return build(status, code, params, instancePath, null);
    }

    /**
     * Construye un {@link ProblemDetail} permitiendo forzar el {@code detail}.
     * Útil para el handler de {@code ResponseStatusException} legacy, que
     * conserva el {@code reason} original como {@code detail}.
     *
     * @param status HTTP status
     * @param code identificador estable del error
     * @param params parámetros nombrados; pueden ser {@code null}
     * @param instancePath URI del request; puede ser {@code null}
     * @param overrideDetail texto a usar como {@code detail} en lugar del resuelto desde {@link MessageSource};
     *                       si es {@code null} o vacío se resuelve del bundle
     * @return el {@link ProblemDetail} listo para serializar
     */
    public ProblemDetail build(
            HttpStatus status,
            String code,
            Map<String, Object> params,
            String instancePath,
            String overrideDetail) {
        Locale locale = LocaleContextHolder.getLocale();
        String detail = (overrideDetail != null && !overrideDetail.isBlank())
                ? overrideDetail
                : resolveMessage(code, params, locale, status);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (instancePath != null) {
            problem.setInstance(URI.create(instancePath));
        }
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        if (params != null && !params.isEmpty()) {
            problem.setProperty("params", params);
        }
        return problem;
    }

    /**
     * Construye un {@link ProblemDetail} para errores de validación con la
     * lista de {@code errors[]} por campo.
     *
     * @param status HTTP status (típicamente 400)
     * @param code code agregado (típicamente {@code validation.failed})
     * @param instancePath URI del request
     * @param errors lista de mapas con al menos {@code field}, {@code code} y {@code message}
     * @return el {@link ProblemDetail} listo para serializar
     */
    public ProblemDetail buildValidation(
            HttpStatus status,
            String code,
            String instancePath,
            List<Map<String, Object>> errors) {
        ProblemDetail pd = build(status, code, null, instancePath);
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return pd;
    }

    private String resolveMessage(String code, Map<String, Object> params, Locale locale, HttpStatus status) {
        Object[] args = paramsToArgs(params);
        try {
            return messageSource.getMessage("errors." + code, args, locale);
        } catch (NoSuchMessageException ignored) {
            // try generic
        }
        try {
            return messageSource.getMessage("errors.generic." + status.value(), null, locale);
        } catch (NoSuchMessageException ignored) {
            // last resort
        }
        return code;
    }

    private Object[] paramsToArgs(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new Object[0];
        }
        return params.values().toArray();
    }
}

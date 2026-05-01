package com.stocka.backend.modules.common.error;

import java.util.Collections;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Excepción de dominio que el {@link GlobalExceptionHandler} traduce a un
 * {@link org.springframework.http.ProblemDetail} con un {@code code} estable
 * e idioma resuelto vía {@code Accept-Language}.
 *
 * <p>El mensaje visible no se pasa por código: se resuelve en el handler
 * buscando la clave {@code errors.<code>} en el {@link org.springframework.context.MessageSource},
 * de modo que cada code tenga obligatoriamente traducción en
 * {@code messages_es/ca/en.properties}.
 *
 * @see ErrorCodes
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> params;

    /**
     * Crea una excepción sin parámetros adicionales.
     *
     * @param status HTTP status que se devolverá al cliente
     * @param code identificador estable del error (ver {@link ErrorCodes})
     */
    public ApiException(HttpStatus status, String code) {
        this(status, code, null, null);
    }

    /**
     * Crea una excepción con parámetros para interpolar en el mensaje localizado
     * y exponer al frontend en el campo {@code params} del cuerpo del error.
     *
     * @param status HTTP status que se devolverá al cliente
     * @param code identificador estable del error (ver {@link ErrorCodes})
     * @param params parámetros nombrados; pueden ser {@code null}
     */
    public ApiException(HttpStatus status, String code, Map<String, Object> params) {
        this(status, code, params, null);
    }

    /**
     * Crea una excepción envolviendo otra causa.
     *
     * @param status HTTP status que se devolverá al cliente
     * @param code identificador estable del error
     * @param params parámetros nombrados; pueden ser {@code null}
     * @param cause excepción original
     */
    public ApiException(HttpStatus status, String code, Map<String, Object> params, Throwable cause) {
        super(code, cause);
        this.status = status;
        this.code = code;
        this.params = params == null ? Collections.emptyMap() : Map.copyOf(params);
    }

    /**
     * @return HTTP status al que se traduce esta excepción
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * @return code estable del error (formato {@code <modulo>.<evento>})
     */
    public String getCode() {
        return code;
    }

    /**
     * @return parámetros nombrados, nunca {@code null}
     */
    public Map<String, Object> getParams() {
        return params;
    }
}

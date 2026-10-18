package com.stocka.backend.modules.security.error;

import java.util.Map;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import jakarta.servlet.RequestDispatcher;

/**
 * Error attributes of Spring Boot's {@code /error} endpoint that expose the error message for
 * client errors (4xx) only.
 *
 * <p>{@code /error} is reached when a filter calls {@code sendError()} or throws before any
 * controller runs. The common case is the authorization server rejecting a {@code client_id} or
 * {@code redirect_uri}: its message ("[invalid_request] OAuth 2.0 Parameter: client_id") is exactly
 * what the person in the browser needs to see. Server errors keep Boot's default (no message), so
 * exception texts never leak to clients.
 */
@Component
public class SafeErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Object status = webRequest.getAttribute(RequestDispatcher.ERROR_STATUS_CODE, RequestAttributes.SCOPE_REQUEST);
        boolean clientError = status instanceof Integer code && code >= 400 && code < 500;
        ErrorAttributeOptions effective = clientError
                ? options.including(Include.MESSAGE)
                : options.excluding(Include.MESSAGE);
        return super.getErrorAttributes(webRequest, effective);
    }
}

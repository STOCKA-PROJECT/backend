package com.stocka.backend.modules.notifications.email;

import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Component
public class EmailTemplateRenderer {
    private final SpringTemplateEngine templateEngine;
    private final MessageSource messageSource;

    public EmailTemplateRenderer(SpringTemplateEngine templateEngine, MessageSource messageSource) {
        this.templateEngine = templateEngine;
        this.messageSource = messageSource;
    }

    public RenderedEmail render(
            String templateName,
            String subjectKey,
            Object[] subjectArgs,
            Locale locale,
            Map<String, Object> variables
    ) {
        Context ctx = new Context(locale);
        if (variables != null) {
            variables.forEach(ctx::setVariable);
        }
        String html = templateEngine.process("email/" + templateName, ctx);
        String subject = messageSource.getMessage(subjectKey, subjectArgs, locale);
        return new RenderedEmail(subject, html);
    }
}

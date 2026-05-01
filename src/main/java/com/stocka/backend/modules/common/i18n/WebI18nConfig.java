package com.stocka.backend.modules.common.i18n;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Configura la resolución de locale a partir de la cabecera {@code Accept-Language}.
 * Locales soportados: español (default), catalán, inglés.
 */
@Configuration
public class WebI18nConfig {

    /**
     * @return resolver de locale basado en {@code Accept-Language} con español por defecto
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(
                Locale.of("es"),
                Locale.of("ca"),
                Locale.of("en")
        ));
        resolver.setDefaultLocale(Locale.of("es"));
        return resolver;
    }
}

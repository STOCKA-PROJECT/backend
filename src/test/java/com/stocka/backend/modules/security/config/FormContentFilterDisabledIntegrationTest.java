package com.stocka.backend.modules.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.FormContentFilter;

import jakarta.servlet.ServletException;

/**
 * Regresión para Sentry {@code STOCKA-119437818}: una petición PATCH/PUT/DELETE
 * con {@code Content-Type: application/x-www-form-urlencoded} y un cuerpo que
 * contenga un {@code %} sin codificar (por ejemplo {@code foo=%@bar}) hacía que
 * el {@code FormContentFilter} de Spring llamara a {@code URLDecoder.decode} y
 * lanzara un {@link IllegalArgumentException} que Tomcat traducía a 500.
 *
 * <p>La API de Stocka es JSON-only, por lo que {@code FormContentFilter} se
 * desactiva en {@code application.properties} mediante
 * {@code spring.mvc.formcontent.filter.enabled=false}.
 *
 * <p>Los tests cubren dos planos:
 * <ul>
 *   <li>Que la auto-configuración de Spring Boot <strong>no</strong> registra
 *       ningún bean de {@link FormContentFilter} (el bean ausente es la única
 *       garantía real en producción, ya que la cadena de filtros de Tomcat se
 *       puebla desde el {@code ApplicationContext}).</li>
 *   <li>Que, de seguir activo, el filtro habría convertido un body malformado
 *       en un 500. Se reproduce instanciando un {@link FormContentFilter} de
 *       prueba y comprobando que sí lanza {@link IllegalArgumentException}.
 *       Este test es el "control negativo": si algún día el comportamiento de
 *       Spring cambiara y el filtro dejara de fallar con {@code %@}, este test
 *       fallaría y revisaríamos si la desactivación sigue siendo necesaria.</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("FormContentFilter disabled (Sentry STOCKA-119437818)")
class FormContentFilterDisabledIntegrationTest {

    private static final String MALFORMED_FORM_BODY = "password=foo%@bar";

    @Autowired private ApplicationContext context;

    @Nested
    @DisplayName("Spring auto-configuration")
    class AutoConfiguration {

        @Test
        @DisplayName("does not register any FormContentFilter bean in the context")
        void should_notRegisterFormContentFilterBean() {
            assertThat(context.getBeansOfType(FormContentFilter.class)).isEmpty();
        }

        @Test
        @DisplayName("does not expose a 'formContentFilter' bean by name")
        void should_notExposeFormContentFilterBeanByName() {
            assertThat(context.containsBean("formContentFilter")).isFalse();
        }
    }

    @Nested
    @DisplayName("Control: confirm FormContentFilter would still misbehave on a malformed body")
    class ControlScenario {

        @Test
        @DisplayName("instantiated FormContentFilter blows up on '%@' body (root cause: IllegalArgumentException)")
        void should_confirmFilterWouldMisbehave_when_bodyContainsUnescapedPercent() {
            FormContentFilter filter = new FormContentFilter();
            MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/auth/login");
            request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
            request.setContent(MALFORMED_FORM_BODY.getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // Spring wraps the URLDecoder failure as HttpMessageNotReadableException, with
            // the underlying IllegalArgumentException ("Illegal hex characters in escape (%) pattern")
            // as the cause — this is what surfaces in Sentry STOCKA-119437818 as a 500.
            assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                    .isInstanceOf(HttpMessageNotReadableException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("URLDecoder: Illegal hex characters in escape (%) pattern - "
                            + "not a hexadecimal digit: \"@\" = 64");
        }

        @Test
        @DisplayName("instantiated FormContentFilter passes through a well-formed body without throwing")
        void should_passThrough_when_bodyIsWellFormed() throws IOException, ServletException {
            FormContentFilter filter = new FormContentFilter();
            MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/auth/login");
            request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
            request.setContent("password=foobar".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isLessThan(500);
        }
    }
}

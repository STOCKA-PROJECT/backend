package com.stocka.backend.modules.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;

/**
 * Regresión para el 400 de Tomcat «Invalid character found in the request target»
 * al filtrar piezas por atributo.
 *
 * <p>El filtro {@code attr} del listado de piezas codifica sus valores como
 * {@code <scope>:<attributeId>:<v1>|<v2>|...} y los clientes WHATWG (navegadores,
 * fetch/undici, ofetch/ufo) envían el separador {@code |} sin percent-encodear,
 * porque no pertenece a su query percent-encode set. Tomcat, en cambio, aplica la
 * gramática estricta de RFC 3986 (donde {@code |} es inválido) y rechazaba la
 * petición con 400 antes de llegar a Spring. Se corrige con
 * {@code server.tomcat.relaxed-query-chars=|} en {@code application.properties}.
 *
 * <p>Los tests hablan HTTP crudo por socket porque tanto {@link java.net.URI}
 * como los clientes HTTP de test de Spring validan RFC 3986 y se niegan a emitir
 * un {@code |} literal — exactamente el carácter que hay que poner en el cable
 * para reproducir el fallo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("server.tomcat.relaxed-query-chars — pipe en el filtro attr de piezas")
class RelaxedQueryCharsIntegrationTest {

    private static final int READ_TIMEOUT_MS = 5_000;

    @LocalServerPort private int port;

    @Autowired private TomcatServerProperties tomcatProperties;

    /**
     * Envía una petición GET cruda (sin validación RFC 3986 del lado cliente) y
     * devuelve el código de estado de la respuesta.
     *
     * @param target request target tal cual irá en la request line, query incluida
     * @return el código de estado HTTP de la respuesta
     * @throws IOException si falla la conexión o la lectura de la respuesta
     */
    private int rawGetStatus(String target) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            assertThat(statusLine).as("status line").isNotNull().startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    @Nested
    @DisplayName("Configuración")
    class Configuration {

        @Test
        @DisplayName("relaxed-query-chars contiene exactamente el pipe")
        void should_relaxOnlyPipe() {
            assertThat(tomcatProperties.getRelaxedQueryChars()).containsExactly('|');
        }
    }

    @Nested
    @DisplayName("Peticiones con | literal en la query")
    class PipeInQuery {

        @Test
        @DisplayName("un endpoint público con | en la query responde 200, no 400")
        void should_return200_when_publicEndpointReceivesLiteralPipeInQuery() throws IOException {
            assertThat(rawGetStatus("/health?attr=ORG:1:|2026-07-24")).isEqualTo(200);
        }

        @Test
        @DisplayName("el listado de piezas con filtro attr de rango llega hasta Spring Security (401, no 400)")
        void should_reachSpringSecurity_when_piecesListFilteredByAttributeRange() throws IOException {
            // La forma exacta que emite el frontend: rango de fechas con "desde" vacío.
            // Sin autenticación esperamos el 401 de JsonAuthenticationEntryPoint; antes
            // del fix, Tomcat cortaba con 400 sin llegar siquiera al filtro JWT.
            int status = rawGetStatus("/organizations/demo/pieces"
                    + "?page=0&size=20&sort=updatedAt,desc&attr=ORG:1:|2026-07-24");
            assertThat(status).isEqualTo(401);
        }

        @Test
        @DisplayName("varios valores separados por | también se aceptan")
        void should_return200_when_queryContainsMultiplePipeSeparatedValues() throws IOException {
            assertThat(rawGetStatus("/health?attr=TYPE:7:rojo|verde|azul")).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Control negativo: el resto de caracteres inválidos siguen rechazados")
    class OtherInvalidCharsStillRejected {

        @ParameterizedTest(name = "query con ''{0}'' literal → 400")
        @DisplayName("caracteres fuera de RFC 3986 distintos de | siguen dando 400")
        @ValueSource(strings = {"{", "}", "\"", "^", "`", "\\"})
        void should_return400_when_queryContainsOtherInvalidCharacter(String invalidChar) throws IOException {
            assertThat(rawGetStatus("/health?x=" + invalidChar)).isEqualTo(400);
        }
    }
}

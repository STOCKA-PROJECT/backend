package com.stocka.backend.modules.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResendSender")
class ResendSenderTest {

    @Mock private Resend resendClient;
    @Mock private Emails emails;

    private ResendSender sut;

    @BeforeEach
    void setUp() {
        lenient().when(resendClient.emails()).thenReturn(emails);
        sut = new ResendSender(resendClient);
    }

    @Nested
    @DisplayName("send (success path)")
    class Success {

        @Test
        @DisplayName("returns the Resend message id when the API call succeeds")
        void should_returnMessageId_whenApiCallSucceeds() throws ResendException {
            CreateEmailResponse response = new CreateEmailResponse("msg_123");
            when(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class))).thenReturn(response);

            String id = sut.send("from@x", "to@x", "subj", "<p>body</p>", "k/1");

            assertThat(id).isEqualTo("msg_123");
        }

        @Test
        @DisplayName("forwards from/to/subject/html to the SDK builder")
        void should_forwardFieldsToBuilder() throws ResendException {
            when(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                    .thenReturn(new CreateEmailResponse("id"));
            ArgumentCaptor<CreateEmailOptions> optionsCaptor = ArgumentCaptor.forClass(CreateEmailOptions.class);

            sut.send("noreply@stocka", "user@x", "Subject A", "<h1>HI</h1>", "k/1");

            verify(emails).send(optionsCaptor.capture(), any(RequestOptions.class));
            CreateEmailOptions opts = optionsCaptor.getValue();
            assertThat(opts.getFrom()).isEqualTo("noreply@stocka");
            assertThat(opts.getTo()).containsExactly("user@x");
            assertThat(opts.getSubject()).isEqualTo("Subject A");
            assertThat(opts.getHtml()).isEqualTo("<h1>HI</h1>");
        }

        @Test
        @DisplayName("attaches the idempotency key to the request options")
        void should_setIdempotencyKey() throws ResendException {
            when(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                    .thenReturn(new CreateEmailResponse("id"));
            ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);

            sut.send("from@x", "to@x", "s", "<p/>", "password-reset/abcdef");

            verify(emails).send(any(CreateEmailOptions.class), optsCaptor.capture());
            assertThat(optsCaptor.getValue().getIdempotencyKey()).isEqualTo("password-reset/abcdef");
        }
    }

    @Nested
    @DisplayName("send (transient failures)")
    class Transient {

        @ParameterizedTest(name = "HTTP {0} is treated as transient and triggers a retry")
        @ValueSource(ints = {429, 500, 502, 503, 504, 599})
        void should_throwTransient_forRetryableStatuses(int status) throws ResendException {
            when(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                    .thenThrow(new ResendException(status, "boom"));

            assertThatThrownBy(() -> sut.send("from", "to", "s", "<p/>", "k/1"))
                    .isInstanceOf(ResendTransientException.class)
                    .hasMessageContaining(String.valueOf(status));
        }

        @Test
        @DisplayName("treats null status (network error) as transient")
        void should_throwTransient_whenStatusIsNull() throws ResendException {
            when(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                    .thenThrow(new ResendException("network down"));

            assertThatThrownBy(() -> sut.send("from", "to", "s", "<p/>", "k/1"))
                    .isInstanceOf(ResendTransientException.class);
        }

        @Test
        @DisplayName("wraps unexpected RuntimeException as transient so Spring Retry can retry")
        void should_throwTransient_whenSdkThrowsRuntime() throws ResendException {
            when(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                    .thenThrow(new RuntimeException("kaboom"));

            assertThatThrownBy(() -> sut.send("from", "to", "s", "<p/>", "k/1"))
                    .isInstanceOf(ResendTransientException.class)
                    .hasRootCauseMessage("kaboom");
        }
    }

    @Nested
    @DisplayName("send (non-retryable failures)")
    class NonRetryable {

        @ParameterizedTest(name = "HTTP {0} is logged and swallowed (returns null)")
        @ValueSource(ints = {400, 401, 403, 404, 409, 422})
        void should_returnNull_forNonRetryableStatuses(int status) throws ResendException {
            when(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                    .thenThrow(new ResendException(status, "client error"));

            String id = sut.send("from", "to", "s", "<p/>", "k/1");

            assertThat(id).isNull();
        }

        @Test
        @DisplayName("does not wrap non-retryable errors in ResendTransientException")
        void should_notThrowTransient_forValidationError() throws ResendException {
            when(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                    .thenThrow(new ResendException(422, "invalid email"));

            // Reaching this point means no exception was thrown to the caller,
            // i.e. the retry framework will not invoke the method again.
            String id = sut.send("from", "to", "s", "<p/>", "k/1");
            assertThat(id).isNull();
        }
    }
}

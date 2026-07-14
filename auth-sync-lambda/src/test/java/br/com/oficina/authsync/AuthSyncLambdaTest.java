package br.com.oficina.authsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthSyncLambdaTest {
    @Test
    void deveRetornarSomenteFalhasParciaisDoLote() throws Exception {
        var store = mock(AuthUserProjectionStore.class);
        var handler = new AuthSyncLambda();
        handler.store = store;
        handler.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        var valid = message("valid", validBody(handler.objectMapper));
        var invalid = message("invalid", "nao-e-json");
        var event = new SQSEvent();
        event.setRecords(List.of(valid, invalid));
        var context = mock(Context.class);
        var logger = mock(LambdaLogger.class);
        org.mockito.Mockito.when(context.getLogger()).thenReturn(logger);

        var response = handler.handleRequest(event, context);

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("invalid", response.getBatchItemFailures().getFirst().getItemIdentifier());
        verify(store).apply(any(DomainEventEnvelope.class));
    }

    private SQSEvent.SQSMessage message(String id, String body) {
        var message = new SQSEvent.SQSMessage();
        message.setMessageId(id);
        message.setBody(body);
        return message;
    }

    private String validBody(ObjectMapper mapper) throws Exception {
        var event = new DomainEventEnvelope(
                UUID.randomUUID(),
                "usuarioAdicionado",
                1,
                OffsetDateTime.now(ZoneOffset.UTC),
                "oficina-os-service",
                UUID.randomUUID().toString(),
                Map.of());
        return mapper.writeValueAsString(Map.of("Message", mapper.writeValueAsString(event)));
    }
}

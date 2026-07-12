package br.com.oficina.autenticacao.sync;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

@Named("usuario-sync")
public class UsuarioSyncHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private static final Logger LOG = Logger.getLogger(UsuarioSyncHandler.class);

    private final ObjectMapper objectMapper;
    private final UsuarioSyncService service;

    @Inject
    UsuarioSyncHandler(ObjectMapper objectMapper, UsuarioSyncService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent input, Context context) {
        var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
        if (input == null || input.getRecords() == null) {
            return new SQSBatchResponse(failures);
        }

        for (var message : input.getRecords()) {
            try {
                var event = decode(message.getBody());
                service.synchronize(event);
                LOG.infov(
                        "user event processed eventId={0} eventType={1} aggregateId={2}",
                        event.eventId(),
                        event.eventType(),
                        event.aggregateId());
            } catch (RuntimeException exception) {
                LOG.errorv(
                        exception,
                        "user event synchronization failed messageId={0}",
                        message.getMessageId());
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }

    private DomainEventEnvelope decode(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Mensagem SQS sem corpo.");
        }
        try {
            var root = objectMapper.readTree(body);
            if (root.hasNonNull("Message")) {
                var message = root.get("Message");
                body = message.isTextual() ? message.asText() : message.toString();
            }
            return objectMapper.readValue(body, DomainEventEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Mensagem SQS não contém evento de usuário válido.", exception);
        }
    }
}

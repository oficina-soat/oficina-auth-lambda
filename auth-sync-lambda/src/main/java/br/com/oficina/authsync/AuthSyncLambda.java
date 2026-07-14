package br.com.oficina.authsync;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@Named("authSync")
public class AuthSyncLambda implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private static final Logger LOG = Logger.getLogger(AuthSyncLambda.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    @Inject ObjectMapper objectMapper;
    @Inject AuthUserProjectionStore store;

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
        if (event == null || event.getRecords() == null) {
            return new SQSBatchResponse(failures);
        }
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                store.apply(decode(message.getBody()));
            } catch (RuntimeException exception) {
                LOG.errorf(exception, "Falha ao sincronizar usuario da mensagem %s", message.getMessageId());
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }

    private DomainEventEnvelope decode(String body) {
        try {
            var root = objectMapper.readTree(body);
            var envelope = unwrapEnvelope(root);
            return new DomainEventEnvelope(
                    UUID.fromString(envelope.path("eventId").asText()),
                    envelope.path("eventType").asText(),
                    envelope.path("eventVersion").asInt(),
                    parseOccurredAt(envelope.path("occurredAt")),
                    envelope.path("producer").asText(),
                    envelope.path("aggregateId").asText(),
                    objectMapper.convertValue(envelope.path("payload"), PAYLOAD_TYPE));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Mensagem SQS invalida.", exception);
        }
    }

    private JsonNode unwrapEnvelope(JsonNode root) throws Exception {
        if (!root.hasNonNull("Message")) {
            return root;
        }
        var message = root.get("Message");
        return message.isTextual() ? objectMapper.readTree(message.asText()) : message;
    }

    private OffsetDateTime parseOccurredAt(JsonNode occurredAt) {
        if (occurredAt.isNumber()) {
            var seconds = occurredAt.decimalValue();
            long epochSeconds = seconds.longValue();
            int nanos = seconds.subtract(java.math.BigDecimal.valueOf(epochSeconds))
                    .movePointRight(9)
                    .intValue();
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds, nanos), ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(occurredAt.asText());
    }
}

package br.com.oficina.authsync;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.ArrayList;

@Named("authSync")
public class AuthSyncLambda implements RequestHandler<SQSEvent, SQSBatchResponse> {
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
                context.getLogger().log("Falha ao sincronizar usuario: " + exception.getClass().getSimpleName());
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }

    private DomainEventEnvelope decode(String body) {
        try {
            var root = objectMapper.readTree(body);
            String domainBody = root.hasNonNull("Message") ? root.get("Message").asText() : body;
            return objectMapper.readValue(domainBody, DomainEventEnvelope.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Mensagem SQS invalida.", exception);
        }
    }
}

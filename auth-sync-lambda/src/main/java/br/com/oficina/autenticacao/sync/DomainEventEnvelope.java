package br.com.oficina.autenticacao.sync;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DomainEventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        OffsetDateTime occurredAt,
        String producer,
        UUID aggregateId,
        UsuarioOperacionalSnapshot payload) {
}

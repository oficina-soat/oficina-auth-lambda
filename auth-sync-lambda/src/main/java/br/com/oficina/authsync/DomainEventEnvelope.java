package br.com.oficina.authsync;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record DomainEventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        OffsetDateTime occurredAt,
        String producer,
        String aggregateId,
        Map<String, Object> payload) {}

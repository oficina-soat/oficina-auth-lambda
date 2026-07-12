package br.com.oficina.autenticacao.sync;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

@ApplicationScoped
class UsuarioSyncEventValidator {
    private static final Set<String> EVENT_TYPES = Set.of(
            "usuarioAdicionado",
            "usuarioAtualizado",
            "usuarioExcluido");
    private static final Set<String> STATUSES = Set.of("ATIVO", "INATIVO", "BLOQUEADO");
    private static final Set<String> ROLES = Set.of("administrativo", "mecanico", "recepcionista");

    void validate(DomainEventEnvelope event) {
        if (event == null
                || event.eventId() == null
                || !EVENT_TYPES.contains(event.eventType())
                || event.eventVersion() != 1
                || event.occurredAt() == null
                || !"oficina-os-service".equals(event.producer())
                || event.aggregateId() == null
                || event.payload() == null) {
            throw invalid("Envelope de evento de usuário inválido.");
        }

        var payload = event.payload();
        if (payload.usuarioId() == null
                || payload.pessoaId() == null
                || !event.aggregateId().equals(payload.usuarioId())
                || payload.nome() == null
                || payload.nome().isBlank()
                || payload.nome().length() > 255
                || payload.documento() == null
                || !payload.documento().matches("[0-9]{11}")
                || !STATUSES.contains(payload.status())
                || payload.papeis() == null
                || payload.papeis().isEmpty()
                || payload.papeis().stream().anyMatch(role -> !ROLES.contains(role))
                || payload.papeis().stream().distinct().count() != payload.papeis().size()
                || payload.atualizadoEm() == null) {
            throw invalid("Snapshot operacional de usuário inválido.");
        }
        if ("usuarioExcluido".equals(event.eventType()) && !"INATIVO".equals(payload.status())) {
            throw invalid("usuarioExcluido deve possuir status INATIVO.");
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

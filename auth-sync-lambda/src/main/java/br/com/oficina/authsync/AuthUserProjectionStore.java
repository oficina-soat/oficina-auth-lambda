package br.com.oficina.authsync;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class AuthUserProjectionStore {
    @Inject DataSource dataSource;

    @Transactional
    public void apply(DomainEventEnvelope event) {
        validate(event);
        try (var connection = dataSource.getConnection()) {
            if (alreadyConsumed(connection, event.eventId())) {
                return;
            }
            Map<String, Object> payload = event.payload();
            UUID usuarioId = UUID.fromString(payload.get("usuarioId").toString());
            OffsetDateTime atualizadoEm = OffsetDateTime.parse(payload.get("atualizadoEm").toString());
            if (!isNewer(connection, usuarioId, atualizadoEm)) {
                recordConsumed(connection, event);
                return;
            }

            long pessoaId = upsertPessoa(connection, payload);
            long internalUserId = upsertUsuario(connection, usuarioId, pessoaId, payload, atualizadoEm);
            replaceRoles(connection, internalUserId, roles(payload));
            recordConsumed(connection, event);
        } catch (SQLException exception) {
            throw new IllegalStateException("Falha ao projetar usuario no store de autenticacao.", exception);
        }
    }

    private boolean alreadyConsumed(java.sql.Connection connection, UUID eventId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM auth_consumed_event WHERE event_id = ?")) {
            statement.setObject(1, eventId);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean isNewer(java.sql.Connection connection, UUID usuarioId, OffsetDateTime updatedAt) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT last_event_at FROM usuario WHERE external_id = ?")) {
            statement.setObject(1, usuarioId);
            try (var result = statement.executeQuery()) {
                return !result.next() || result.getObject(1, OffsetDateTime.class) == null
                        || result.getObject(1, OffsetDateTime.class).isBefore(updatedAt);
            }
        }
    }

    private long upsertPessoa(java.sql.Connection connection, Map<String, Object> payload) throws SQLException {
        UUID externalId = UUID.fromString(payload.get("pessoaId").toString());
        String documento = payload.get("documento").toString();
        String nome = payload.get("nome").toString();
        try (var select = connection.prepareStatement(
                "SELECT id FROM pessoa WHERE external_id = ? OR documento = ? FOR UPDATE")) {
            select.setObject(1, externalId);
            select.setString(2, documento);
            try (var result = select.executeQuery()) {
                if (result.next()) {
                    long pessoaId = result.getLong(1);
                    if (result.next()) {
                        throw new IllegalStateException("Pessoa externa e documento apontam para cadastros distintos.");
                    }
                    try (var update = connection.prepareStatement(
                            "UPDATE pessoa SET external_id = ?, documento = ?, nome = ? WHERE id = ?")) {
                        update.setObject(1, externalId);
                        update.setString(2, documento);
                        update.setString(3, nome);
                        update.setLong(4, pessoaId);
                        update.executeUpdate();
                    }
                    return pessoaId;
                }
            }
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO pessoa (id, external_id, documento, tipo_pessoa, nome)
                VALUES (nextval('pessoa_seq'), ?, ?, 'FISICA', ?)
                RETURNING id
                """)) {
            statement.setObject(1, externalId);
            statement.setString(2, documento);
            statement.setString(3, nome);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long upsertUsuario(
            java.sql.Connection connection,
            UUID externalId,
            long pessoaId,
            Map<String, Object> payload,
            OffsetDateTime updatedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO usuario (id, external_id, pessoa_id, password, status, last_event_at)
                VALUES (nextval('usuario_seq'), ?, ?, NULL, ?, ?)
                ON CONFLICT (external_id) DO UPDATE
                SET pessoa_id = EXCLUDED.pessoa_id,
                    status = EXCLUDED.status,
                    last_event_at = EXCLUDED.last_event_at
                RETURNING id
                """)) {
            statement.setObject(1, externalId);
            statement.setLong(2, pessoaId);
            statement.setString(3, payload.get("status").toString());
            statement.setObject(4, updatedAt);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void replaceRoles(java.sql.Connection connection, long userId, List<String> roles) throws SQLException {
        try (var delete = connection.prepareStatement("DELETE FROM usuario_papel WHERE usuario_id = ?")) {
            delete.setLong(1, userId);
            delete.executeUpdate();
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO usuario_papel (usuario_id, papel_id)
                SELECT ?, id FROM papel WHERE nome = ?
                """)) {
            for (String role : roles) {
                insert.setLong(1, userId);
                insert.setString(2, role);
                if (insert.executeUpdate() != 1) {
                    throw new IllegalArgumentException("Papel operacional desconhecido: " + role);
                }
            }
        }
    }

    private void recordConsumed(java.sql.Connection connection, DomainEventEnvelope event) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO auth_consumed_event (event_id, aggregate_id, event_type, occurred_at, consumed_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """)) {
            statement.setObject(1, event.eventId());
            statement.setString(2, event.aggregateId());
            statement.setString(3, event.eventType());
            statement.setObject(4, event.occurredAt());
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> roles(Map<String, Object> payload) {
        return ((List<Object>) payload.get("papeis")).stream().map(Object::toString).sorted().toList();
    }

    private void validate(DomainEventEnvelope event) {
        if (event == null || event.eventId() == null || event.payload() == null
                || !List.of("usuarioAdicionado", "usuarioAtualizado", "usuarioExcluido").contains(event.eventType())) {
            throw new IllegalArgumentException("Evento de usuario invalido.");
        }
    }
}

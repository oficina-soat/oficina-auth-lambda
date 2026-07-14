package br.com.oficina.authsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthUserProjectionStoreIT {
    @Inject AuthUserProjectionStore store;
    @Inject DataSource dataSource;

    @Test
    void deveAplicarSnapshotPreservarCredencialEIgnorarEventoAntigoOuDuplicado() throws SQLException {
        UUID usuarioId = UUID.randomUUID();
        UUID pessoaId = UUID.randomUUID();
        OffsetDateTime firstTime = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
        var first = event(UUID.randomUUID(), "usuarioAdicionado", usuarioId, pessoaId, "ATIVO", firstTime);

        store.apply(first);
        store.apply(first);
        assertProjection(usuarioId, "ATIVO", 1, null);

        setPassword(usuarioId, "bcrypt-preservado");
        store.apply(event(UUID.randomUUID(), "usuarioAtualizado", usuarioId, pessoaId, "BLOQUEADO", firstTime.plusMinutes(1)));
        store.apply(event(UUID.randomUUID(), "usuarioAtualizado", usuarioId, pessoaId, "ATIVO", firstTime));

        assertProjection(usuarioId, "BLOQUEADO", 3, "bcrypt-preservado");
    }

    private DomainEventEnvelope event(
            UUID eventId, String type, UUID userId, UUID personId, String status, OffsetDateTime occurredAt) {
        return new DomainEventEnvelope(
                eventId,
                type,
                1,
                occurredAt,
                "oficina-os-service",
                userId.toString(),
                Map.of(
                        "usuarioId", userId.toString(),
                        "pessoaId", personId.toString(),
                        "nome", "Usuario Projetado",
                        "documento", "52998224725",
                        "status", status,
                        "papeis", List.of("mecanico"),
                        "atualizadoEm", occurredAt.toString()));
    }

    private void assertProjection(UUID userId, String status, int consumed, String password) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT status, password FROM usuario WHERE external_id = ?")) {
            statement.setObject(1, userId);
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(status, result.getString("status"));
                if (password == null) assertNull(result.getString("password"));
                else assertEquals(password, result.getString("password"));
            }
        }
        assertEquals(consumed, count("auth_consumed_event"));
    }

    private void setPassword(UUID userId, String password) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("UPDATE usuario SET password = ? WHERE external_id = ?")) {
            statement.setString(1, password);
            statement.setObject(2, userId);
            statement.executeUpdate();
        }
    }

    private int count(String table) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}

package br.com.oficina.autenticacao.sync;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
class UsuarioSyncService {
    private static final String CONSUMER = "oficina-auth-sync-lambda";

    private final DataSource dataSource;
    private final UsuarioSyncEventValidator validator;

    @Inject
    UsuarioSyncService(DataSource dataSource, UsuarioSyncEventValidator validator) {
        this.dataSource = dataSource;
        this.validator = validator;
    }

    @Transactional
    public void synchronize(DomainEventEnvelope event) {
        validator.validate(event);
        try (var connection = dataSource.getConnection()) {
            if (!registerEvent(connection, event)) {
                return;
            }
            synchronizeUser(connection, event);
        } catch (SQLException exception) {
            throw new IllegalStateException("Falha ao sincronizar usuário no store de autenticação.", exception);
        }
    }

    private boolean registerEvent(Connection connection, DomainEventEnvelope event) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO evento_processado (consumer, event_id, event_type, processed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """)) {
            statement.setString(1, CONSUMER);
            statement.setObject(2, event.eventId());
            statement.setString(3, event.eventType());
            statement.setObject(4, OffsetDateTime.now(ZoneOffset.UTC));
            return statement.executeUpdate() == 1;
        }
    }

    private void synchronizeUser(Connection connection, DomainEventEnvelope event) throws SQLException {
        var snapshot = event.payload();
        var existing = findUserByOperationalId(connection, snapshot.usuarioId());
        if (existing != null) {
            if (isObsolete(event, existing)) {
                return;
            }
            ensureDocumentAvailable(connection, snapshot.documento(), existing.pessoaId());
            updatePerson(connection, existing.pessoaId(), snapshot);
            updateUser(connection, existing.id(), snapshot, event.occurredAt());
            replaceRoles(connection, existing.id(), snapshot);
            return;
        }

        var pessoaId = findPersonByDocument(connection, snapshot.documento());
        if (pessoaId == null) {
            pessoaId = nextValue(connection, "pessoa_seq");
            insertPerson(connection, pessoaId, snapshot);
        } else {
            updatePerson(connection, pessoaId, snapshot);
        }

        existing = findUserByPersonId(connection, pessoaId);
        long usuarioId;
        if (existing == null) {
            usuarioId = nextValue(connection, "usuario_seq");
            insertUser(connection, usuarioId, pessoaId, snapshot, event.occurredAt());
        } else {
            if (existing.operationalId() != null && !existing.operationalId().equals(snapshot.usuarioId())) {
                throw new IllegalStateException("CPF já projetado para outro usuário operacional.");
            }
            if (isObsolete(event, existing)) {
                return;
            }
            usuarioId = existing.id();
            updateUser(connection, usuarioId, snapshot, event.occurredAt());
        }
        replaceRoles(connection, usuarioId, snapshot);
    }

    private boolean isObsolete(DomainEventEnvelope event, UserRow existing) {
        return existing.lastOperationalEventAt() != null
                && !event.occurredAt().isAfter(existing.lastOperationalEventAt());
    }

    private UserRow findUserByOperationalId(Connection connection, UUID operationalId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id, pessoa_id, usuario_operacional_id, ultimo_evento_operacional_em
                FROM usuario
                WHERE usuario_operacional_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, operationalId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? userRow(resultSet) : null;
            }
        }
    }

    private UserRow findUserByPersonId(Connection connection, long pessoaId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id, pessoa_id, usuario_operacional_id, ultimo_evento_operacional_em
                FROM usuario
                WHERE pessoa_id = ?
                FOR UPDATE
                """)) {
            statement.setLong(1, pessoaId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? userRow(resultSet) : null;
            }
        }
    }

    private Long findPersonByDocument(Connection connection, String document) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM pessoa WHERE documento = ? FOR UPDATE")) {
            statement.setString(1, document);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private void ensureDocumentAvailable(Connection connection, String document, long currentPersonId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM pessoa WHERE documento = ? AND id <> ?")) {
            statement.setString(1, document);
            statement.setLong(2, currentPersonId);
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new IllegalStateException("CPF já projetado para outra pessoa no store de autenticação.");
                }
            }
        }
    }

    private long nextValue(Connection connection, String sequence) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT nextval('" + sequence + "')");
                var resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Sequência sem próximo valor: " + sequence);
            }
            return resultSet.getLong(1);
        }
    }

    private void insertPerson(Connection connection, long pessoaId, UsuarioOperacionalSnapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO pessoa (id, documento, tipo_pessoa, nome)
                VALUES (?, ?, 'FISICA', ?)
                """)) {
            statement.setLong(1, pessoaId);
            statement.setString(2, snapshot.documento());
            statement.setString(3, snapshot.nome());
            statement.executeUpdate();
        }
    }

    private void updatePerson(Connection connection, long pessoaId, UsuarioOperacionalSnapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE pessoa
                SET documento = ?, tipo_pessoa = 'FISICA', nome = ?
                WHERE id = ?
                """)) {
            statement.setString(1, snapshot.documento());
            statement.setString(2, snapshot.nome());
            statement.setLong(3, pessoaId);
            statement.executeUpdate();
        }
    }

    private void insertUser(
            Connection connection,
            long usuarioId,
            long pessoaId,
            UsuarioOperacionalSnapshot snapshot,
            OffsetDateTime occurredAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO usuario (
                    id, pessoa_id, usuario_operacional_id, password, status, ultimo_evento_operacional_em
                ) VALUES (?, ?, ?, NULL, ?, ?)
                """)) {
            statement.setLong(1, usuarioId);
            statement.setLong(2, pessoaId);
            statement.setObject(3, snapshot.usuarioId());
            statement.setString(4, snapshot.status());
            statement.setObject(5, occurredAt);
            statement.executeUpdate();
        }
    }

    private void updateUser(
            Connection connection,
            long usuarioId,
            UsuarioOperacionalSnapshot snapshot,
            OffsetDateTime occurredAt)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE usuario
                SET usuario_operacional_id = ?, status = ?, ultimo_evento_operacional_em = ?
                WHERE id = ?
                """)) {
            statement.setObject(1, snapshot.usuarioId());
            statement.setString(2, snapshot.status());
            statement.setObject(3, occurredAt);
            statement.setLong(4, usuarioId);
            statement.executeUpdate();
        }
    }

    private void replaceRoles(Connection connection, long usuarioId, UsuarioOperacionalSnapshot snapshot)
            throws SQLException {
        try (var delete = connection.prepareStatement("DELETE FROM usuario_papel WHERE usuario_id = ?")) {
            delete.setLong(1, usuarioId);
            delete.executeUpdate();
        }

        var roleIds = roleIds(connection);
        try (var insert = connection.prepareStatement(
                "INSERT INTO usuario_papel (usuario_id, papel_id) VALUES (?, ?)")) {
            for (var role : snapshot.papeis()) {
                var roleId = roleIds.get(role);
                if (roleId == null) {
                    throw new IllegalStateException("Papel canônico ausente no store de autenticação: " + role);
                }
                insert.setLong(1, usuarioId);
                insert.setLong(2, roleId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private Map<String, Long> roleIds(Connection connection) throws SQLException {
        var result = new LinkedHashMap<String, Long>();
        try (var statement = connection.prepareStatement("SELECT id, nome FROM papel");
                var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                result.put(resultSet.getString("nome"), resultSet.getLong("id"));
            }
        }
        return result;
    }

    private UserRow userRow(ResultSet resultSet) throws SQLException {
        return new UserRow(
                resultSet.getLong("id"),
                resultSet.getLong("pessoa_id"),
                resultSet.getObject("usuario_operacional_id", UUID.class),
                resultSet.getObject("ultimo_evento_operacional_em", OffsetDateTime.class));
    }

    private record UserRow(
            long id,
            long pessoaId,
            UUID operationalId,
            OffsetDateTime lastOperationalEventAt) {
    }
}

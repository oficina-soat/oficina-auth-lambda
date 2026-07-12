package br.com.oficina.autenticacao.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@QuarkusTest
@QuarkusTestResource(value = UsuarioSyncHandlerTest.PostgresResource.class, restrictToAnnotatedClass = true)
class UsuarioSyncHandlerTest {
    private static final UUID USUARIO_ID = UUID.fromString("5c9528f8-8f0f-45f6-a142-e1fc1f587d91");
    private static final UUID PESSOA_ID = UUID.fromString("29d83ef5-0cbe-4e3d-8f58-ef97a38984da");

    @Inject
    UsuarioSyncHandler handler;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS evento_processado CASCADE");
            statement.execute("DROP TABLE IF EXISTS usuario_papel CASCADE");
            statement.execute("DROP TABLE IF EXISTS usuario CASCADE");
            statement.execute("DROP TABLE IF EXISTS papel CASCADE");
            statement.execute("DROP TABLE IF EXISTS pessoa CASCADE");
            statement.execute("DROP SEQUENCE IF EXISTS usuario_seq");
            statement.execute("DROP SEQUENCE IF EXISTS papel_seq");
            statement.execute("DROP SEQUENCE IF EXISTS pessoa_seq");
            statement.execute("CREATE SEQUENCE pessoa_seq START WITH 1 INCREMENT BY 1");
            statement.execute("CREATE SEQUENCE papel_seq START WITH 1 INCREMENT BY 1");
            statement.execute("CREATE SEQUENCE usuario_seq START WITH 1 INCREMENT BY 1");
            statement.execute("""
                    CREATE TABLE pessoa (
                        id bigint PRIMARY KEY,
                        documento varchar(255) NOT NULL UNIQUE,
                        tipo_pessoa varchar(20) NOT NULL,
                        nome varchar(255)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE papel (
                        id bigint PRIMARY KEY,
                        nome varchar(255) NOT NULL UNIQUE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE usuario (
                        id bigint PRIMARY KEY,
                        pessoa_id bigint NOT NULL UNIQUE REFERENCES pessoa(id),
                        usuario_operacional_id uuid UNIQUE,
                        password varchar(255),
                        status varchar(255) NOT NULL,
                        ultimo_evento_operacional_em timestamp with time zone
                    )
                    """);
            statement.execute("""
                    CREATE TABLE usuario_papel (
                        usuario_id bigint NOT NULL REFERENCES usuario(id),
                        papel_id bigint NOT NULL REFERENCES papel(id),
                        PRIMARY KEY (usuario_id, papel_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE evento_processado (
                        consumer varchar(100) NOT NULL,
                        event_id uuid NOT NULL,
                        event_type varchar(100) NOT NULL,
                        processed_at timestamp with time zone NOT NULL,
                        PRIMARY KEY (consumer, event_id)
                    )
                    """);
            statement.execute("""
                    INSERT INTO papel (id, nome) VALUES
                        (1, 'administrativo'),
                        (2, 'mecanico'),
                        (3, 'recepcionista')
                    """);
            statement.execute("ALTER SEQUENCE papel_seq RESTART WITH 4");
        }
    }

    @Test
    void deveCriarAtualizarEInativarProjecaoIdempotenteSemCredencial() throws Exception {
        var adicionado = event(
                UUID.randomUUID(),
                "usuarioAdicionado",
                "Ana Silva",
                "84191404067",
                "ATIVO",
                List.of("mecanico"));

        assertTrue(handle(message("add-1", adicionado)).getBatchItemFailures().isEmpty());
        assertTrue(handle(message("add-duplicate", adicionado)).getBatchItemFailures().isEmpty());
        assertUser("Ana Silva", "84191404067", "ATIVO", null, List.of("mecanico"));
        assertEquals(1, count("evento_processado"));

        var atualizado = event(
                UUID.randomUUID(),
                "usuarioAtualizado",
                "Ana Silva Atualizada",
                "52998224725",
                "BLOQUEADO",
                List.of("administrativo", "recepcionista"));
        assertTrue(handle(message("update-1", atualizado)).getBatchItemFailures().isEmpty());
        assertUser(
                "Ana Silva Atualizada",
                "52998224725",
                "BLOQUEADO",
                null,
                List.of("administrativo", "recepcionista"));

        var excluido = event(
                UUID.randomUUID(),
                "usuarioExcluido",
                "Ana Silva Atualizada",
                "52998224725",
                "INATIVO",
                List.of("administrativo", "recepcionista"));
        assertTrue(handle(message("delete-1", excluido)).getBatchItemFailures().isEmpty());
        assertUser(
                "Ana Silva Atualizada",
                "52998224725",
                "INATIVO",
                null,
                List.of("administrativo", "recepcionista"));
        assertEquals(3, count("evento_processado"));
    }

    @Test
    void devePreservarSenhaAoAdotarUsuarioSeedadoPeloCpf() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO pessoa (id, documento, tipo_pessoa, nome) VALUES (10, '84191404067', 'FISICA', 'Seed')");
            statement.execute("INSERT INTO usuario (id, pessoa_id, password, status) VALUES (10, 10, 'bcrypt-existente', 'ATIVO')");
            statement.execute("INSERT INTO usuario_papel (usuario_id, papel_id) VALUES (10, 1)");
        }

        var adicionado = event(
                UUID.randomUUID(),
                "usuarioAdicionado",
                "Administrador Sincronizado",
                "84191404067",
                "ATIVO",
                List.of("administrativo", "mecanico"));

        assertTrue(handle(message("seed-adoption", adicionado)).getBatchItemFailures().isEmpty());
        assertUser(
                "Administrador Sincronizado",
                "84191404067",
                "ATIVO",
                "bcrypt-existente",
                List.of("administrativo", "mecanico"));
    }

    @Test
    void deveRetornarSomenteItemInvalidoComoFalhaParcial() throws Exception {
        var valido = event(
                UUID.randomUUID(),
                "usuarioAdicionado",
                "Operador Válido",
                "84191404067",
                "ATIVO",
                List.of("mecanico"));
        var invalid = new SQSEvent.SQSMessage();
        invalid.setMessageId("invalid-1");
        invalid.setBody("{nao-json}");

        var response = handle(message("valid-1", valido), invalid);

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("invalid-1", response.getBatchItemFailures().getFirst().getItemIdentifier());
        assertEquals(1, count("usuario"));
        assertEquals(1, count("evento_processado"));
    }

    @Test
    void deveIgnorarSnapshotAntigoEntregueDepoisDaExclusao() throws Exception {
        var referencia = OffsetDateTime.now(ZoneOffset.UTC);
        var excluido = event(
                UUID.randomUUID(),
                "usuarioExcluido",
                "Operador Inativo",
                "84191404067",
                "INATIVO",
                List.of("mecanico"),
                referencia.plusSeconds(2));
        var adicionadoAntigo = event(
                UUID.randomUUID(),
                "usuarioAdicionado",
                "Operador Antigo",
                "84191404067",
                "ATIVO",
                List.of("administrativo"),
                referencia);

        assertTrue(handle(message("delete-newer", excluido)).getBatchItemFailures().isEmpty());
        assertTrue(handle(message("add-older", adicionadoAntigo)).getBatchItemFailures().isEmpty());

        assertUser("Operador Inativo", "84191404067", "INATIVO", null, List.of("mecanico"));
        assertEquals(2, count("evento_processado"));
    }

    private com.amazonaws.services.lambda.runtime.events.SQSBatchResponse handle(SQSEvent.SQSMessage... messages) {
        var event = new SQSEvent();
        event.setRecords(List.of(messages));
        return handler.handleRequest(event, null);
    }

    private SQSEvent.SQSMessage message(String messageId, DomainEventEnvelope event) throws Exception {
        var message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(objectMapper.writeValueAsString(event));
        return message;
    }

    private DomainEventEnvelope event(
            UUID eventId,
            String eventType,
            String nome,
            String documento,
            String status,
            List<String> papeis) {
        return event(eventId, eventType, nome, documento, status, papeis, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private DomainEventEnvelope event(
            UUID eventId,
            String eventType,
            String nome,
            String documento,
            String status,
            List<String> papeis,
            OffsetDateTime occurredAt) {
        return new DomainEventEnvelope(
                eventId,
                eventType,
                1,
                occurredAt,
                "oficina-os-service",
                USUARIO_ID,
                new UsuarioOperacionalSnapshot(
                        USUARIO_ID,
                        PESSOA_ID,
                        nome,
                        documento,
                        status,
                        papeis,
                        occurredAt));
    }

    private void assertUser(
            String nome,
            String documento,
            String status,
            String password,
            List<String> papeis) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT p.nome, p.documento, u.usuario_operacional_id, u.password, u.status,
                               string_agg(pa.nome, ',' ORDER BY pa.nome) AS papeis
                        FROM usuario u
                        JOIN pessoa p ON p.id = u.pessoa_id
                        JOIN usuario_papel up ON up.usuario_id = u.id
                        JOIN papel pa ON pa.id = up.papel_id
                        WHERE u.usuario_operacional_id = ?
                        GROUP BY p.nome, p.documento, u.usuario_operacional_id, u.password, u.status
                        """)) {
            statement.setObject(1, USUARIO_ID);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(nome, resultSet.getString("nome"));
                assertEquals(documento, resultSet.getString("documento"));
                assertEquals(USUARIO_ID, resultSet.getObject("usuario_operacional_id", UUID.class));
                if (password == null) {
                    assertNull(resultSet.getString("password"));
                } else {
                    assertEquals(password, resultSet.getString("password"));
                }
                assertEquals(status, resultSet.getString("status"));
                assertEquals(papeis.stream().sorted().toList(), List.of(resultSet.getString("papeis").split(",")));
            }
        }
    }

    private int count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT count(*) FROM " + table);
                var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    public static class PostgresResource implements QuarkusTestResourceLifecycleManager {
        private PostgreSQLContainer postgres;

        @Override
        public Map<String, String> start() {
            postgres = new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("auth_sync")
                    .withUsername("auth_sync_user")
                    .withPassword("auth_sync_password");
            postgres.start();
            return Map.of(
                    "quarkus.datasource.jdbc.url", postgres.getJdbcUrl(),
                    "quarkus.datasource.username", postgres.getUsername(),
                    "quarkus.datasource.password", postgres.getPassword());
        }

        @Override
        public void stop() {
            if (postgres != null) {
                postgres.stop();
            }
        }
    }
}

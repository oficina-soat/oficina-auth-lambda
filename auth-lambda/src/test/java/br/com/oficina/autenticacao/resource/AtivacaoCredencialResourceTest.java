package br.com.oficina.autenticacao.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.autenticacao.domain.TipoPessoa;
import br.com.oficina.autenticacao.domain.UsuarioStatus;
import br.com.oficina.autenticacao.persistence.AtivacaoCredencialEntity;
import br.com.oficina.autenticacao.persistence.PapelEntity;
import br.com.oficina.autenticacao.persistence.PessoaEntity;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AtivacaoTokenResponse;
import br.com.oficina.autenticacao.resource.dto.AtivarCredencialRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AtivacaoCredencialResourceTest {
    private static final UUID USUARIO_ID = UUID.fromString("5c9528f8-8f0f-45f6-a142-e1fc1f587d91");
    private static final String DOCUMENTO = "39053344705";
    private static final String NOVA_SENHA = "senha-segura-123";

    @BeforeEach
    void resetProjectedUser() {
        QuarkusTransaction.requiringNew().run(() -> {
            var user = UsuarioEntity.findByOperationalId(USUARIO_ID);
            if (user == null) {
                var person = new PessoaEntity();
                person.documento = DOCUMENTO;
                person.tipoPessoa = TipoPessoa.FISICA;
                person.nome = "Operador a ativar";
                person.persist();

                user = new UsuarioEntity();
                user.usuarioOperacionalId = USUARIO_ID;
                user.pessoa = person;
                user.status = UsuarioStatus.ATIVO;
                user.papelEntities.add(PapelEntity.find("nome", "recepcionista").singleResult());
                user.persist();
            } else {
                AtivacaoCredencialEntity.delete("usuario", user);
                user.pessoa.documento = DOCUMENTO;
                user.pessoa.nome = "Operador a ativar";
                user.status = UsuarioStatus.ATIVO;
                user.password = null;
                user.papelEntities.clear();
                user.papelEntities.add(PapelEntity.find("nome", "recepcionista").singleResult());
            }
        });
    }

    @Test
    void deveAtivarCredencialComTokenUnicoSemExporSenhaAoCadastroOperacional() {
        var adminToken = authenticate("84191404067", "secret");

        var first = requestActivation(adminToken);
        var second = requestActivation(adminToken);

        assertEquals(43, first.token().length());
        assertEquals(43, second.token().length());
        assertNotEquals(first.token(), second.token());
        assertTrue(second.expiresAt().isAfter(java.time.OffsetDateTime.now()));

        given()
                .contentType(ContentType.JSON)
                .body(new AtivarCredencialRequest(first.token(), NOVA_SENHA))
                .when()
                .post("/auth/ativacoes")
                .then()
                .statusCode(400)
                .body("message", equalTo("Token de ativação inválido"));

        given()
                .contentType(ContentType.JSON)
                .body(new AtivarCredencialRequest(second.token(), NOVA_SENHA))
                .when()
                .post("/auth/ativacoes")
                .then()
                .statusCode(204);

        var stored = storedCredential();
        assertNotEquals(second.token(), stored.tokenHash());
        assertEquals(64, stored.tokenHash().length());
        assertTrue(BcryptUtil.matches(NOVA_SENHA, stored.passwordHash()));
        assertTrue(stored.used());

        var authenticated = given()
                .contentType(ContentType.JSON)
                .body(new AutenticarUsuarioRequest(DOCUMENTO, NOVA_SENHA))
                .when()
                .post("/auth/token")
                .then()
                .statusCode(200)
                .extract()
                .as(AutenticarUsuarioResponse.class);
        assertFalse(authenticated.access_token().isBlank());

        given()
                .contentType(ContentType.JSON)
                .body(new AtivarCredencialRequest(second.token(), NOVA_SENHA))
                .when()
                .post("/auth/ativacoes")
                .then()
                .statusCode(400)
                .body("message", equalTo("Token de ativação inválido"));

        given()
                .auth().oauth2(adminToken)
                .when()
                .post("/auth/usuarios/{usuarioId}/ativacao", USUARIO_ID)
                .then()
                .statusCode(409);
    }

    @Test
    void deveProtegerSolicitacaoAdministrativaERecusarEstadoBloqueado() {
        given()
                .when()
                .post("/auth/usuarios/{usuarioId}/ativacao", USUARIO_ID)
                .then()
                .statusCode(401);

        QuarkusTransaction.requiringNew().run(() ->
                UsuarioEntity.findByOperationalId(USUARIO_ID).password = BcryptUtil.bcryptHash("secret"));
        var nonAdminToken = authenticate(DOCUMENTO, "secret");
        given()
                .auth().oauth2(nonAdminToken)
                .when()
                .post("/auth/usuarios/{usuarioId}/ativacao", USUARIO_ID)
                .then()
                .statusCode(403);

        QuarkusTransaction.requiringNew().run(() -> {
            var user = UsuarioEntity.findByOperationalId(USUARIO_ID);
            user.status = UsuarioStatus.BLOQUEADO;
            user.password = null;
        });

        var adminToken = authenticate("84191404067", "secret");
        given()
                .auth().oauth2(adminToken)
                .when()
                .post("/auth/usuarios/{usuarioId}/ativacao", USUARIO_ID)
                .then()
                .statusCode(409)
                .body("message", equalTo("Ativação de credencial indisponível para o estado atual do usuário"));

        given()
                .auth().oauth2(adminToken)
                .when()
                .post("/auth/usuarios/{usuarioId}/ativacao", UUID.randomUUID())
                .then()
                .statusCode(404);
    }

    @Test
    void deveRecusarSenhaForaDaPolitica() {
        var activation = requestActivation(authenticate("84191404067", "secret"));

        given()
                .contentType(ContentType.JSON)
                .body(new AtivarCredencialRequest(activation.token(), "curta"))
                .when()
                .post("/auth/ativacoes")
                .then()
                .statusCode(400)
                .body("message", equalTo("Senha deve ter entre 12 e 128 caracteres"));
    }

    private AtivacaoTokenResponse requestActivation(String adminToken) {
        return given()
                .auth().oauth2(adminToken)
                .accept(ContentType.JSON)
                .when()
                .post("/auth/usuarios/{usuarioId}/ativacao", USUARIO_ID)
                .then()
                .statusCode(201)
                .extract()
                .as(AtivacaoTokenResponse.class);
    }

    private String authenticate(String documento, String password) {
        return given()
                .contentType(ContentType.JSON)
                .body(new AutenticarUsuarioRequest(documento, password))
                .when()
                .post("/auth/token")
                .then()
                .statusCode(200)
                .extract()
                .as(AutenticarUsuarioResponse.class)
                .access_token();
    }

    private StoredCredential storedCredential() {
        var result = new AtomicReference<StoredCredential>();
        QuarkusTransaction.requiringNew().run(() -> {
            var user = UsuarioEntity.findByOperationalId(USUARIO_ID);
            var activation = (AtivacaoCredencialEntity) AtivacaoCredencialEntity.find("usuario", user).singleResult();
            result.set(new StoredCredential(
                    activation.tokenHash,
                    user.password,
                    activation.usedAt != null));
        });
        return result.get();
    }

    private record StoredCredential(String tokenHash, String passwordHash, boolean used) {
    }
}

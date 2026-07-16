package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.UsuarioStatus;
import br.com.oficina.autenticacao.domain.TipoPessoa;
import br.com.oficina.autenticacao.persistence.PapelEntity;
import br.com.oficina.autenticacao.persistence.PessoaEntity;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class UsuarioResourceQuarkusTest {

    private static final String DOCUMENTO_ATIVO = "52998224725";
    private static final String SENHA_CORRETA = "secret";
    private static final Set<String> DEFAULT_AUDIENCES = Set.of(
            "oficina-os-service",
            "oficina-billing-service",
            "oficina-execution-service");

    @Inject
    JWTParser jwtParser;

    @BeforeEach
    void setUp() {
        persistUsuarioIfMissing(DOCUMENTO_ATIVO, SENHA_CORRETA, "administrativo", "mecanico", "recepcionista");
    }

    @Test
    void shouldAuthenticateUsingHttpEndpoint() throws Exception {
        var response = given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(new AutenticarUsuarioRequest(DOCUMENTO_ATIVO, SENHA_CORRETA))
                .when()
                .post("/auth")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .header("X-Request-Id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyOrNullString()))
                .extract()
                .as(AutenticarUsuarioResponse.class);

        var token = jwtParser.parse(response.access_token());

        assertEquals("Bearer", response.token_type());
        assertEquals(3600, response.expires_in());
        assertEquals(DOCUMENTO_ATIVO, token.getSubject());
        assertEquals(DEFAULT_AUDIENCES, token.getAudience());
        assertEquals(Set.of("administrativo", "mecanico", "recepcionista"), token.getGroups());
    }

    @Test
    void shouldReturnUnauthorizedForAuthenticationFailure() {
        given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(new AutenticarUsuarioRequest(DOCUMENTO_ATIVO, "senha-incorreta"))
                .when()
                .post("/auth")
                .then()
                .statusCode(401)
                .contentType(ContentType.JSON)
                .body("message", equalTo("Credenciais inválidas"))
                .body("motivo", equalTo("Senha inválida"));
    }

    @Test
    void shouldReturnBadRequestForMissingCredentials() {
        given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(new AutenticarUsuarioRequest("   ", SENHA_CORRETA))
                .when()
                .post("/auth")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", equalTo("cpf e password são obrigatórios"))
                .body("motivo", equalTo("cpf e password são obrigatórios"));
    }

    @Test
    void shouldReturnBadRequestForInvalidCpf() {
        given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(new AutenticarUsuarioRequest("84191404066", SENHA_CORRETA))
                .when()
                .post("/auth")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", equalTo("CPF inválido: 84191404066"))
                .body("motivo", equalTo("CPF inválido: 84191404066"));
    }

    @Test
    void shouldExposeSanitizedCredentialStatusToAdministrator() {
        var externalId = prepareUserWithoutCredential();
        var adminToken = given()
                .contentType(ContentType.JSON)
                .body(new AutenticarUsuarioRequest(DOCUMENTO_ATIVO, SENHA_CORRETA))
                .post("/auth/token")
                .then()
                .statusCode(200)
                .extract()
                .path("access_token")
                .toString();

        given()
                .auth().oauth2(adminToken)
                .get("/auth/usuarios/{usuarioId}/credencial", externalId)
                .then()
                .statusCode(200)
                .body("status", equalTo("NAO_ATIVADA"))
                .body("acoesPermitidas[0]", equalTo("SOLICITAR_ATIVACAO"))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("password")))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("token")));

        given()
                .auth().oauth2(adminToken)
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/auth/usuarios/{usuarioId}/ativacao", externalId)
                .then()
                .statusCode(201);

        given()
                .auth().oauth2(adminToken)
                .get("/auth/usuarios/{usuarioId}/credencial", externalId)
                .then()
                .statusCode(200)
                .body("status", equalTo("ATIVACAO_PENDENTE"))
                .body("expiresAt", org.hamcrest.Matchers.notNullValue());
    }

    private static UUID prepareUserWithoutCredential() {
        var externalId = UUID.fromString("30000000-0000-4000-8000-000000000099");
        QuarkusTransaction.requiringNew().run(() -> {
            var existing = UsuarioEntity.findByExternalId(externalId);
            if (existing != null) {
                existing.password = null;
                br.com.oficina.autenticacao.persistence.AtivacaoTokenEntity.delete("usuario", existing);
                return;
            }
            var pessoa = new PessoaEntity();
            pessoa.documento = "16899535009";
            pessoa.tipoPessoa = TipoPessoa.FISICA;
            pessoa.persist();
            var usuario = new UsuarioEntity();
            usuario.externalId = externalId;
            usuario.pessoa = pessoa;
            usuario.status = UsuarioStatus.ATIVO;
            usuario.papelEntities.add(PapelEntity.<PapelEntity>find("nome", "mecanico").firstResult());
            usuario.persist();
        });
        return externalId;
    }

    private static void persistUsuarioIfMissing(String documento, String password, String... papeis) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (UsuarioEntity.findByDocumento(documento) != null) {
                return;
            }

            PessoaEntity pessoa = new PessoaEntity();
            pessoa.documento = documento;
            pessoa.tipoPessoa = TipoPessoa.FISICA;

            List<PapelEntity> papelEntities = PapelEntity.list("nome in ?1", List.of(papeis));
            if (papelEntities.size() != papeis.length) {
                throw new IllegalStateException("Nem todos os papeis esperados foram encontrados");
            }

            UsuarioEntity usuario = new UsuarioEntity();
            usuario.pessoa = pessoa;
            usuario.password = BcryptUtil.bcryptHash(password);
            usuario.status = UsuarioStatus.ATIVO;
            usuario.papelEntities.addAll(papelEntities);

            pessoa.persist();
            usuario.persist();
        });
    }
}

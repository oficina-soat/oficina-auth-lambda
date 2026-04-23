package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.UsuarioStatus;
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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class UsuarioResourceQuarkusTest {

    private static final String DOCUMENTO_ATIVO = "52998224725";
    private static final String SENHA_CORRETA = "secret";

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
                .extract()
                .as(AutenticarUsuarioResponse.class);

        var token = jwtParser.parse(response.access_token());

        assertEquals("Bearer", response.token_type());
        assertEquals(3600, response.expires_in());
        assertEquals(DOCUMENTO_ATIVO, token.getSubject());
        assertEquals(Set.of("oficina-app"), token.getAudience());
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

    private static void persistUsuarioIfMissing(String documento, String password, String... papeis) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (UsuarioEntity.findByDocumento(documento) != null) {
                return;
            }

            PessoaEntity pessoa = new PessoaEntity();
            pessoa.documento = documento;

            List<PapelEntity> papelEntities = PapelEntity.list("papel in ?1", List.of(papeis));
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

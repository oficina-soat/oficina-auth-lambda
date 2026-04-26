package br.com.oficina.notificacao.resource;

import br.com.oficina.notificacao.resource.dto.EnviarEmailRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class NotificacaoResourceQuarkusTest {

    @Test
    void deveResponderNoContentNoEndpointHttp() {
        given()
                .contentType(ContentType.JSON)
                .body(new EnviarEmailRequest(
                        "cliente@oficina.com",
                        "Assunto",
                        "Mensagem"))
                .when()
                .post("/notificacoes/email")
                .then()
                .statusCode(204);
    }
}

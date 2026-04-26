package br.com.oficina.notificacao.resource;

import br.com.oficina.notificacao.domain.EnviarEmailUseCase;
import br.com.oficina.notificacao.resource.dto.EnviarEmailRequest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificacaoResourceTest {

    @Test
    void deveDelegarEnvioDeEmailParaUseCase() {
        var useCase = Mockito.mock(EnviarEmailUseCase.class);
        var resource = new NotificacaoResource(useCase);
        var request = new EnviarEmailRequest("cliente@oficina.com", "Assunto", "Mensagem");

        Response response = resource.enviarEmail(request);

        assertEquals(204, response.getStatus());
        Mockito.verify(useCase).execute(new EnviarEmailUseCase.Command(
                "cliente@oficina.com",
                "Assunto",
                "Mensagem"));
    }
}

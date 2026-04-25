package br.com.oficina.notificacao.domain;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnviarEmailUseCaseTest {

    @Test
    void deveEnviarEmailComMensagemAssuntoEDestinatarioInformados() {
        var mailer = mock(ReactiveMailer.class);
        when(mailer.send(org.mockito.ArgumentMatchers.any(Mail[].class)))
                .thenReturn(Uni.createFrom().voidItem());
        var useCase = new EnviarEmailUseCase(mailer, "noreply@oficina.com");

        useCase.execute(new EnviarEmailUseCase.Command(
                "cliente@oficina.com",
                "Assunto de teste",
                "Mensagem de teste"));

        var mailCaptor = ArgumentCaptor.forClass(Mail[].class);
        verify(mailer).send(mailCaptor.capture());

        var mail = mailCaptor.getValue()[0];
        assertEquals("cliente@oficina.com", mail.getTo().getFirst());
        assertEquals("Assunto de teste", mail.getSubject());
        assertEquals("Mensagem de teste", mail.getText());
        assertEquals("noreply@oficina.com", mail.getFrom());
    }

    @Test
    void deveFalharQuandoEmailDestinoNaoEstiverInformado() {
        var useCase = new EnviarEmailUseCase(mock(ReactiveMailer.class), "noreply@oficina.com");

        var exception = assertThrows(BadRequestException.class,
                () -> useCase.execute(new EnviarEmailUseCase.Command(" ", "Assunto", "Mensagem")));

        assertEquals("emailDestino e obrigatorio", exception.getMessage());
    }

    @Test
    void deveFalharComMensagemClaraQuandoRemetenteNaoEstiverConfigurado() {
        var useCase = new EnviarEmailUseCase(mock(ReactiveMailer.class), " ");

        var exception = assertThrows(IllegalStateException.class,
                () -> useCase.execute(new EnviarEmailUseCase.Command(
                        "cliente@oficina.com",
                        "Assunto de teste",
                        "Mensagem de teste")));

        assertEquals("Configuracao quarkus.mailer.from e obrigatoria para envio de e-mail", exception.getMessage());
    }
}

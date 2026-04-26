package br.com.oficina.notificacao.domain;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EnviarEmailUseCase {

    private final ReactiveMailer mailer;
    private final String from;

    public EnviarEmailUseCase(ReactiveMailer mailer,
                              @ConfigProperty(name = "quarkus.mailer.from") String from) {
        this.mailer = mailer;
        this.from = from;
    }

    public void execute(Command command) {
        validar(command);

        if (from == null || from.isBlank()) {
            throw new IllegalStateException("Configuracao quarkus.mailer.from e obrigatoria para envio de e-mail");
        }

        var mail = Mail.withText(command.emailDestino(), command.assunto(), command.conteudo());
        mail.setFrom(from);
        mailer.send(mail).await().indefinitely();
    }

    private static void validar(Command command) {
        if (command == null) {
            throw new BadRequestException("Corpo da notificacao e obrigatorio");
        }
        if (command.emailDestino() == null || command.emailDestino().isBlank()) {
            throw new BadRequestException("emailDestino e obrigatorio");
        }
        if (command.assunto() == null || command.assunto().isBlank()) {
            throw new BadRequestException("assunto e obrigatorio");
        }
        if (command.conteudo() == null || command.conteudo().isBlank()) {
            throw new BadRequestException("conteudo e obrigatorio");
        }
    }

    public record Command(String emailDestino, String assunto, String conteudo) {
    }
}

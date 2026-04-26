package br.com.oficina.notificacao.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class MailerConfigValidator {

    private final Optional<String> from;
    private final Optional<String> host;
    private final boolean mock;

    public MailerConfigValidator(@ConfigProperty(name = "quarkus.mailer.from") Optional<String> from,
                                 @ConfigProperty(name = "quarkus.mailer.host") Optional<String> host,
                                 @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false") boolean mock) {
        this.from = from;
        this.host = host;
        this.mock = mock;
    }

    void onStart(@Observes StartupEvent ignoredEvent) {
        validar();
    }

    void validar() {
        if (from.map(String::trim).filter(value -> !value.isEmpty()).isEmpty()) {
            throw new IllegalStateException("Configuracao quarkus.mailer.from e obrigatoria para inicializar a notificacao-lambda");
        }

        if (!mock && host.map(String::trim).filter(value -> !value.isEmpty()).isPresent()) {
            return;
        }

        if (!mock) {
            throw new IllegalStateException("Configure quarkus.mailer.host ou habilite quarkus.mailer.mock=true para inicializar a notificacao-lambda");
        }
    }
}

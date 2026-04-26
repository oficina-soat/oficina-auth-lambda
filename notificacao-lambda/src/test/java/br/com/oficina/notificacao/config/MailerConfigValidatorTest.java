package br.com.oficina.notificacao.config;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailerConfigValidatorTest {

    @Test
    void deveAceitarMailerMockSemHostQuandoFromEstiverConfigurado() {
        var validator = new MailerConfigValidator(Optional.of("noreply@oficina.com"), Optional.empty(), true);

        assertDoesNotThrow(validator::validar);
    }

    @Test
    void deveExigirFromNoStartup() {
        var validator = new MailerConfigValidator(Optional.of(" "), Optional.of("smtp.oficina.com"), false);

        var exception = assertThrows(IllegalStateException.class, validator::validar);

        assertEquals("Configuracao quarkus.mailer.from e obrigatoria para inicializar a notificacao-lambda", exception.getMessage());
    }

    @Test
    void deveExigirHostQuandoMockNaoEstiverAtivo() {
        var validator = new MailerConfigValidator(Optional.of("noreply@oficina.com"), Optional.of(" "), false);

        var exception = assertThrows(IllegalStateException.class, validator::validar);

        assertEquals("Configure quarkus.mailer.host ou habilite quarkus.mailer.mock=true para inicializar a notificacao-lambda", exception.getMessage());
    }
}

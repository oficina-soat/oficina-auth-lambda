package br.com.oficina.autenticacao.domain.exceptions;

import br.com.oficina.autenticacao.domain.AutenticacaoComMotivo;
import io.quarkus.security.UnauthorizedException;

public class CredenciaisInvalidasException extends UnauthorizedException implements AutenticacaoComMotivo {
    private final String motivo;

    protected CredenciaisInvalidasException(String motivo) {
        super("Credenciais inválidas");
        this.motivo = motivo;
    }

    @Override
    public String motivo() {
        return motivo;
    }
}

package br.com.oficina.autenticacao.domain.exceptions;

import br.com.oficina.autenticacao.domain.AutenticacaoComMotivo;
import jakarta.ws.rs.core.Response;

public abstract class AtivacaoCredencialException extends RuntimeException implements AutenticacaoComMotivo {
    private final Response.Status status;

    protected AtivacaoCredencialException(Response.Status status, String message) {
        super(message);
        this.status = status;
    }

    public Response.Status status() {
        return status;
    }

    @Override
    public String motivo() {
        return getMessage();
    }
}

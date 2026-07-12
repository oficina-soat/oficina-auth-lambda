package br.com.oficina.autenticacao.domain.exceptions;

import jakarta.ws.rs.core.Response;

public class SenhaAtivacaoInvalidaException extends AtivacaoCredencialException {
    public SenhaAtivacaoInvalidaException() {
        super(Response.Status.BAD_REQUEST, "Senha deve ter entre 12 e 128 caracteres");
    }
}

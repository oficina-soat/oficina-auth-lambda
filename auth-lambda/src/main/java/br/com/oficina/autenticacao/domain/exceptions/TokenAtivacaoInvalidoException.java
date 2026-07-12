package br.com.oficina.autenticacao.domain.exceptions;

import jakarta.ws.rs.core.Response;

public class TokenAtivacaoInvalidoException extends AtivacaoCredencialException {
    public TokenAtivacaoInvalidoException() {
        super(Response.Status.BAD_REQUEST, "Token de ativação inválido");
    }
}

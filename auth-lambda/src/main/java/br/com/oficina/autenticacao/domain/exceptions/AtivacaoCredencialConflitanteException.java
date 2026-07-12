package br.com.oficina.autenticacao.domain.exceptions;

import jakarta.ws.rs.core.Response;

public class AtivacaoCredencialConflitanteException extends AtivacaoCredencialException {
    public AtivacaoCredencialConflitanteException() {
        super(Response.Status.CONFLICT, "Ativação de credencial indisponível para o estado atual do usuário");
    }
}

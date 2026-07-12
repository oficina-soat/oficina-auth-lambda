package br.com.oficina.autenticacao.domain.exceptions;

import jakarta.ws.rs.core.Response;

public class UsuarioOperacionalNaoEncontradoException extends AtivacaoCredencialException {
    public UsuarioOperacionalNaoEncontradoException() {
        super(Response.Status.NOT_FOUND, "Usuário operacional não encontrado");
    }
}

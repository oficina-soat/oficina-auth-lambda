package br.com.oficina.autenticacao.domain.exceptions;

public class UsuarioNaoEncontradoException extends CredenciaisInvalidasException {

    public UsuarioNaoEncontradoException() {
        super("Usuário não encontrado");
    }
}

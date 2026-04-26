package br.com.oficina.autenticacao.domain.exceptions;

public class UsuarioInativoException extends CredenciaisInvalidasException {

    public UsuarioInativoException() {
        super("Usuário inativo");
    }
}

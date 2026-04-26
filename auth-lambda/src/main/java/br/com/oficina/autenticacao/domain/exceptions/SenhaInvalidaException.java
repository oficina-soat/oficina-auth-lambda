package br.com.oficina.autenticacao.domain.exceptions;

public class SenhaInvalidaException extends CredenciaisInvalidasException {

    public SenhaInvalidaException() {
        super("Senha inválida");
    }
}

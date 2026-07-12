package br.com.oficina.autenticacao.domain.exceptions;

public class CredencialNaoAtivadaException extends CredenciaisInvalidasException {
    public CredencialNaoAtivadaException() {
        super("Credencial não ativada");
    }
}

package br.com.oficina.autenticacao.domain.exceptions;

import br.com.oficina.autenticacao.domain.AutenticacaoComMotivo;

public class CredenciaisObrigatoriasException extends RuntimeException implements AutenticacaoComMotivo {

    public CredenciaisObrigatoriasException() {
        super("cpf e password são obrigatórios");
    }

    @Override
    public String motivo() {
        return getMessage();
    }
}

package br.com.oficina.autenticacao.domain.exceptions;

import br.com.oficina.autenticacao.domain.AutenticacaoComMotivo;

public class CpfInvalidoException extends RuntimeException implements AutenticacaoComMotivo {

    public CpfInvalidoException(String cpf, Throwable cause) {
        super("CPF inválido: " + cpf, cause);
    }

    @Override
    public String motivo() {
        return getMessage();
    }
}

package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.domain.exceptions.CpfInvalidoException;

import java.util.Objects;

public record Cpf(String valor) {
    public Cpf(String valor) {
        try {
            Objects.requireNonNull(valor, "CPF não pode ser nulo");

            var somenteNumeros = valor.trim().replaceAll("\\D", "");

            if (!isValid(somenteNumeros)) throw new IllegalArgumentException("CPF inválido: " + valor);

            this.valor = somenteNumeros;
        } catch (IllegalArgumentException exception) {
            throw new CpfInvalidoException(valor, exception);
        }
    }

    private boolean isValid(String somenteNumeros) {
        if (somenteNumeros.length() != 11) return false;
        if (somenteNumeros.chars().distinct().count() == 1) return false;

        int primeiroDigito = calcularDigito(somenteNumeros, 9);
        int segundoDigito = calcularDigito(somenteNumeros, 10);

        return primeiroDigito == Character.getNumericValue(somenteNumeros.charAt(9))
                && segundoDigito == Character.getNumericValue(somenteNumeros.charAt(10));
    }

    private int calcularDigito(String cpf, int quantidadePosicoes) {
        int soma = 0;
        int peso = quantidadePosicoes + 1;

        for (int i = 0; i < quantidadePosicoes; i++) {
            int num = Character.getNumericValue(cpf.charAt(i));
            soma += num * peso--;
        }

        int resto = soma % 11;
        return (resto < 2) ? 0 : 11 - resto;
    }
}

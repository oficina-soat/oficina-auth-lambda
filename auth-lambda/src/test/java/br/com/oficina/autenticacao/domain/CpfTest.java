package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.domain.exceptions.CpfInvalidoException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CpfTest {
    @Test
    void deveNormalizarCpfValidoComPontuacaoEEspacos() {
        Cpf cpf = new Cpf(" 841.914.040-67 ");

        Assertions.assertEquals("84191404067", cpf.valor());
    }

    @Test
    void deveAceitarCpfValidoSemPontuacao() {
        Cpf cpf = new Cpf("84191404067");

        Assertions.assertEquals("84191404067", cpf.valor());
    }

    @Test
    void deveAceitarCpfValidoComPrimeiroDigitoVerificadorZero() {
        Cpf cpf = new Cpf("12345678909");

        Assertions.assertEquals("12345678909", cpf.valor());
    }

    @Test
    void deveAceitarCpfValidoComFormatacoesAlternadas() {
        Assertions.assertAll(
                () -> Assertions.assertDoesNotThrow(() -> new Cpf("841.914.04067")),
                () -> Assertions.assertDoesNotThrow(() -> new Cpf("841.914.040-67")),
                () -> Assertions.assertDoesNotThrow(() -> new Cpf("841.914040-67")),
                () -> Assertions.assertDoesNotThrow(() -> new Cpf("841914.04067")),
                () -> Assertions.assertDoesNotThrow(() -> new Cpf("841914.040-67")),
                () -> Assertions.assertDoesNotThrow(() -> new Cpf("841914040-67")));
    }

    @Test
    void deveLancarQuandoCpfForNulo() {
        NullPointerException exception = Assertions.assertThrows(NullPointerException.class, () -> new Cpf(null));

        Assertions.assertEquals("CPF não pode ser nulo", exception.getMessage());
    }

    @Test
    void deveLancarQuandoCpfTiverMenosDeOnzeDigitos() {
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf("8419140406"));
    }

    @Test
    void deveLancarQuandoCpfTiverMaisDeOnzeDigitos() {
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf("84191404068"));
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf("841914040671"));
    }

    @Test
    void deveLancarQuandoTodosOsDigitosForemIguais() {
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf("11111111111"));
    }

    @Test
    void deveLancarQuandoDigitosVerificadoresForemInvalidos() {
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf("84191404066"));
    }

    @Test
    void deveLancarQuandoPrimeiroDigitoVerificadorForInvalido() {
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf("84191404077"));
    }

    @Test
    void deveLancarQuandoValorNaoTiverDigitos() {
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf(""));
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf("   "));
        Assertions.assertThrows(CpfInvalidoException.class, () -> new Cpf("...---"));
    }
}

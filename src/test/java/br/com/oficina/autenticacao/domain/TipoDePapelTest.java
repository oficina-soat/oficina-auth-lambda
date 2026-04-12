package br.com.oficina.autenticacao.domain;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TipoDePapelTest {
    @Test
    void deveExporTodosOsPapeisEsperadosComSeusValores() {
        Assertions.assertAll(
                () -> Assertions.assertEquals(TipoDePapelValues.ADMINISTRATIVO, TipoDePapel.ADMINISTRATIVO.valor()),
                () -> Assertions.assertEquals(TipoDePapelValues.RECEPCIONISTA, TipoDePapel.RECEPCIONISTA.valor()),
                () -> Assertions.assertEquals(TipoDePapelValues.MECANICO, TipoDePapel.MECANICO.valor()));
    }

    @Test
    void deveManterOrdemEsperadaDosPapeis() {
        Assertions.assertArrayEquals(
                new TipoDePapel[]{
                        TipoDePapel.ADMINISTRATIVO,
                        TipoDePapel.RECEPCIONISTA,
                        TipoDePapel.MECANICO},
                TipoDePapel.values());
    }
}

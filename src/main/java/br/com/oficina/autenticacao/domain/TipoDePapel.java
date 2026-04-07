package br.com.oficina.autenticacao.domain;

public enum TipoDePapel {
    ADMINISTRATIVO(TipoDePapelValues.ADMINISTRATIVO),
    RECEPCIONISTA(TipoDePapelValues.RECEPCIONISTA),
    MECANICO(TipoDePapelValues.MECANICO);

    private final String valor;

    TipoDePapel(String valor) {
         this.valor = valor;
    }

    public String valor() {
        return valor;
    }
}

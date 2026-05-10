package br.com.oficina.autenticacao.persistence;

import br.com.oficina.autenticacao.domain.TipoPessoa;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Table(name = "pessoa")
@Entity
public class PessoaEntity extends PanacheEntity {

    @Column(name = "documento", nullable = false, unique = true)
    public String documento;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_pessoa", nullable = false, length = 20)
    public TipoPessoa tipoPessoa;

    @Column(name = "nome")
    public String nome;
}

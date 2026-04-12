package br.com.oficina.autenticacao.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Table(name = "pessoa")
@Entity
public class PessoaEntity extends PanacheEntity {
    
    @Column(name = "documento", nullable = false, unique = true)
    public String documento;
}

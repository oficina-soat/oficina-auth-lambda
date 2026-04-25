package br.com.oficina.autenticacao.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Table(name = "papel")
@Entity
public class PapelEntity extends PanacheEntity {

    @Column(name = "nome", nullable = false, unique = true)
    public String nome;
}

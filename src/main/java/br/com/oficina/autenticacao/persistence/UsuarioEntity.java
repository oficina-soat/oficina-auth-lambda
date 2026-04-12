package br.com.oficina.autenticacao.persistence;

import br.com.oficina.autenticacao.domain.UsuarioStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Table(name = "usuario")
@Entity
public class UsuarioEntity extends PanacheEntity {

    public static final String FIND_BY_DOCUMENTO_QUERY =
            "select distinct u from UsuarioEntity u "
                    + "join fetch u.pessoa "
                    + "left join fetch u.papelEntities "
                    + "where u.pessoa.documento = ?1";

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "pessoa_id", nullable = false, unique = true)
    public PessoaEntity pessoa;

    public String password;

    @ManyToMany
    @JoinTable(
            name = "usuario_papel",
            joinColumns = @JoinColumn(name = "usuario_id"),
            inverseJoinColumns = @JoinColumn(name = "papel_id"))
    public List<PapelEntity> papelEntities = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    public UsuarioStatus status;

    public static UsuarioEntity findByDocumento(String cpf) {
        return UsuarioEntity.find(FIND_BY_DOCUMENTO_QUERY, cpf).firstResult();
    }

    public String documento() {
        return pessoa == null ? null : pessoa.documento;
    }

}

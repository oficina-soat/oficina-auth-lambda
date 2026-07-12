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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @Column(name = "usuario_operacional_id", unique = true)
    public UUID usuarioOperacionalId;

    @Column(name = "password")
    public String password;

    @ManyToMany
    @JoinTable(
            name = "usuario_papel",
            joinColumns = @JoinColumn(name = "usuario_id"),
            inverseJoinColumns = @JoinColumn(name = "papel_id"))
    public List<PapelEntity> papelEntities = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public UsuarioStatus status;

    @Column(name = "ultimo_evento_operacional_em")
    public OffsetDateTime ultimoEventoOperacionalEm;

    public static UsuarioEntity findByDocumento(String cpf) {
        return UsuarioEntity.find(FIND_BY_DOCUMENTO_QUERY, cpf)
                .singleResultOptional()
                .map(UsuarioEntity.class::cast)
                .orElse(null);
    }

    public static UsuarioEntity findByOperationalId(UUID usuarioId) {
        return UsuarioEntity.find("usuarioOperacionalId", usuarioId)
                .singleResultOptional()
                .map(UsuarioEntity.class::cast)
                .orElse(null);
    }

    public String documento() {
        return pessoa == null ? null : pessoa.documento;
    }

}

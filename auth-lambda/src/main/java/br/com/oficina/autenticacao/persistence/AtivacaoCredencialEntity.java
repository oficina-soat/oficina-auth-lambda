package br.com.oficina.autenticacao.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Table(name = "credencial_ativacao")
@Entity
public class AtivacaoCredencialEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false, unique = true)
    public UsuarioEntity usuario;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    public String tokenHash;

    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;

    @Column(name = "used_at")
    public OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 255)
    public String createdBy;
}

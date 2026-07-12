package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.domain.exceptions.AtivacaoCredencialConflitanteException;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioOperacionalNaoEncontradoException;
import br.com.oficina.autenticacao.persistence.AtivacaoCredencialEntity;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AtivacaoTokenResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SolicitarAtivacaoCredencialUseCase {
    private final ActivationTokenCodec tokenCodec = new ActivationTokenCodec();

    @ConfigProperty(name = "oficina.auth.activation.ttl-hours", defaultValue = "24")
    long ttlHours;

    @Transactional
    public AtivacaoTokenResponse execute(UUID usuarioId, String createdBy) {
        if (usuarioId == null) {
            throw new UsuarioOperacionalNaoEncontradoException();
        }
        var usuario = UsuarioEntity.findByOperationalId(usuarioId);
        if (usuario == null) {
            throw new UsuarioOperacionalNaoEncontradoException();
        }
        if (usuario.status != UsuarioStatus.ATIVO || usuario.password != null) {
            throw new AtivacaoCredencialConflitanteException();
        }

        AtivacaoCredencialEntity.delete("usuario", usuario);

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var token = tokenCodec.generate();
        var activation = new AtivacaoCredencialEntity();
        activation.id = UUID.randomUUID();
        activation.usuario = usuario;
        activation.tokenHash = tokenCodec.hash(token);
        activation.createdAt = now;
        activation.expiresAt = now.plusHours(validTtlHours());
        activation.createdBy = normalizedCreatedBy(createdBy);
        activation.persist();

        return new AtivacaoTokenResponse(token, activation.expiresAt);
    }

    private long validTtlHours() {
        if (ttlHours < 1 || ttlHours > 168) {
            throw new IllegalStateException("oficina.auth.activation.ttl-hours deve estar entre 1 e 168.");
        }
        return ttlHours;
    }

    private String normalizedCreatedBy(String createdBy) {
        if (createdBy == null || createdBy.isBlank()) {
            return "administrador-desconhecido";
        }
        var normalized = createdBy.trim();
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }
}

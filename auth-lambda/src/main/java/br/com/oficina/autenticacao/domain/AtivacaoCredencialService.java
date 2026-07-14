package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.persistence.AtivacaoTokenEntity;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AtivacaoRequest;
import br.com.oficina.autenticacao.resource.dto.AtivacaoTokenResponse;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AtivacaoCredencialService {
    private static final int TOKEN_BYTES = 32;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private final SecureRandom secureRandom = new SecureRandom();

    @ConfigProperty(name = "oficina.auth.activation-token-ttl", defaultValue = "PT24H")
    Duration tokenTtl;

    @Transactional
    public AtivacaoTokenResponse solicitar(UUID usuarioId) {
        var usuario = UsuarioEntity.findByExternalId(usuarioId);
        if (usuario == null) {
            throw new NotFoundException("Usuario operacional nao encontrado.");
        }
        if (usuario.status != UsuarioStatus.ATIVO || usuario.password != null) {
            throw new WebApplicationException("Usuario nao esta elegivel para ativacao.", Response.Status.CONFLICT);
        }

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        AtivacaoTokenEntity.<AtivacaoTokenEntity>list(
                        "usuario = ?1 and usedAt is null and invalidatedAt is null", usuario)
                .forEach(tokenAnterior -> tokenAnterior.invalidatedAt = now);

        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var entity = new AtivacaoTokenEntity();
        entity.id = UUID.randomUUID();
        entity.usuario = usuario;
        entity.tokenHash = hash(token);
        entity.expiresAt = now.plus(tokenTtl);
        entity.persist();
        return new AtivacaoTokenResponse(token, entity.expiresAt);
    }

    @Transactional
    public void ativar(AtivacaoRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()
                || request.password() == null
                || request.password().length() < MIN_PASSWORD_LENGTH
                || request.password().length() > MAX_PASSWORD_LENGTH) {
            throw invalidActivation();
        }
        var entity = AtivacaoTokenEntity.<AtivacaoTokenEntity>find("tokenHash", hash(request.token())).firstResult();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (entity == null || entity.usedAt != null || entity.invalidatedAt != null || !entity.expiresAt.isAfter(now)) {
            throw invalidActivation();
        }
        entity.usuario.password = BcryptUtil.bcryptHash(request.password());
        entity.usedAt = now;
    }

    private BadRequestException invalidActivation() {
        return new BadRequestException("Token de ativacao ou senha invalidos.");
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel.", exception);
        }
    }
}

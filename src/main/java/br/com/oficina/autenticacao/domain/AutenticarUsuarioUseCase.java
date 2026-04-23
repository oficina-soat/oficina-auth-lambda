package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.domain.exceptions.CredenciaisObrigatoriasException;
import br.com.oficina.autenticacao.domain.exceptions.SenhaInvalidaException;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioInativoException;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioNaoEncontradoException;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AutenticarUsuarioUseCase {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(60);
    private static final String DEFAULT_ISSUER = "oficina-api";
    private static final String DEFAULT_AUDIENCE = "oficina-app";
    private static final String DEFAULT_SCOPE = "oficina-app";
    private static final String DEFAULT_KEY_ID = "oficina-lab-rsa";

    @ConfigProperty(name = "oficina.auth.issuer", defaultValue = DEFAULT_ISSUER)
    String issuer;

    @ConfigProperty(name = "oficina.auth.audience", defaultValue = DEFAULT_AUDIENCE)
    String audience;

    @ConfigProperty(name = "oficina.auth.scope", defaultValue = DEFAULT_SCOPE)
    String scope;

    @ConfigProperty(name = "oficina.auth.key-id", defaultValue = DEFAULT_KEY_ID)
    String keyId;

    @Transactional
    public AutenticarUsuarioResponse execute(AutenticarUsuarioRequest req) {
        var timing = new AutenticacaoTiming();

        try {
            if (req == null
                    || req.cpf() == null || req.cpf().trim().isEmpty()
                    || req.password() == null || req.password().trim().isEmpty()) {
                throw new CredenciaisObrigatoriasException();
            }

            var cpf = timing.cpf(() -> new Cpf(req.cpf()).valor());
            var usuarioEntity = timing.db(() -> UsuarioEntity.findByDocumento(cpf));

            if (usuarioEntity == null) {
                throw new UsuarioNaoEncontradoException();
            }
            if (usuarioEntity.status != UsuarioStatus.ATIVO) {
                throw new UsuarioInativoException();
            }

            boolean passwordMatches = timing.bcrypt(() -> BcryptUtil.matches(req.password(), usuarioEntity.password));
            if (!passwordMatches) {
                throw new SenhaInvalidaException();
            }

            var now = Instant.now();
            Set<String> grupos = usuarioEntity.papelEntities.stream()
                    .map(papelEntity -> papelEntity.papel)
                    .collect(Collectors.toSet());

            String accessToken = timing.jwt(() -> Jwt.issuer(configured(issuer, DEFAULT_ISSUER))
                    .subject(usuarioEntity.documento())
                    .audience(configured(audience, DEFAULT_AUDIENCE))
                    .scope(configured(scope, DEFAULT_SCOPE))
                    .groups(grupos)
                    .issuedAt(now.getEpochSecond())
                    .expiresAt(now.plus(TOKEN_TTL).getEpochSecond())
                    .jws()
                    .keyId(configured(keyId, DEFAULT_KEY_ID))
                    .sign());

            timing.success();
            return new AutenticarUsuarioResponse(accessToken, "Bearer", (int) TOKEN_TTL.toSeconds());
        } catch (RuntimeException exception) {
            timing.failure(exception);
            throw exception;
        }
    }

    private static String configured(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

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

@ApplicationScoped
public class AutenticarUsuarioUseCase {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(60);
    private static final String ISSUER = "oficina-api";

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

            String accessToken = timing.jwt(() -> Jwt.issuer(ISSUER)
                    .subject(usuarioEntity.documento())
                    .groups(grupos)
                    .issuedAt(now.getEpochSecond())
                    .expiresAt(now.plus(TOKEN_TTL).getEpochSecond())
                    .sign());

            timing.success();
            return new AutenticarUsuarioResponse(accessToken, "Bearer", (int) TOKEN_TTL.toSeconds());
        } catch (RuntimeException exception) {
            timing.failure(exception);
            throw exception;
        }
    }
}

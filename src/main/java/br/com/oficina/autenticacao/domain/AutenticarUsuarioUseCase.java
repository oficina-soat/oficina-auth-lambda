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
import java.util.stream.Collectors;

@ApplicationScoped
public class AutenticarUsuarioUseCase {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(60);
    private static final String ISSUER = "oficina-api";

    @Transactional
    public AutenticarUsuarioResponse execute(AutenticarUsuarioRequest req) {
        if (req == null
                || req.cpf() == null || req.cpf().trim().isEmpty()
                || req.password() == null || req.password().trim().isEmpty()) {
            throw new CredenciaisObrigatoriasException();
        }
        var cpf = new Cpf(req.cpf()).valor();
        var now = Instant.now();

        var usuarioEntity = UsuarioEntity.findByDocumento(cpf);
        if (usuarioEntity == null) {
            throw new UsuarioNaoEncontradoException();
        }
        if (usuarioEntity.status != UsuarioStatus.ATIVO) {
            throw new UsuarioInativoException();
        }
        if (!BcryptUtil.matches(req.password(), usuarioEntity.password)) {
            throw new SenhaInvalidaException();
        }

        return new AutenticarUsuarioResponse(
                Jwt.issuer(ISSUER)
                        .subject(usuarioEntity.documento())
                        .groups(usuarioEntity.papelEntities.stream()
                                .map(papelEntity -> papelEntity.papel)
                                .collect(Collectors.toSet()))
                        .issuedAt(now.getEpochSecond())
                        .expiresAt(now.plus(TOKEN_TTL).getEpochSecond())
                        .sign(),
                "Bearer",
                (int) TOKEN_TTL.toSeconds());
    }
}

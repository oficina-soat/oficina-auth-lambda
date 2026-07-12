package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.domain.exceptions.SenhaAtivacaoInvalidaException;
import br.com.oficina.autenticacao.domain.exceptions.TokenAtivacaoInvalidoException;
import br.com.oficina.autenticacao.persistence.AtivacaoCredencialEntity;
import br.com.oficina.autenticacao.resource.dto.AtivarCredencialRequest;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@ApplicationScoped
public class AtivarCredencialUseCase {
    private final ActivationTokenCodec tokenCodec = new ActivationTokenCodec();

    @Transactional
    public void execute(AtivarCredencialRequest request) {
        validate(request);
        var tokenHash = tokenCodec.hash(request.token());
        var activation = AtivacaoCredencialEntity.find("tokenHash", tokenHash)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOptional()
                .map(AtivacaoCredencialEntity.class::cast)
                .orElseThrow(TokenAtivacaoInvalidoException::new);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (activation.usedAt != null
                || !activation.expiresAt.isAfter(now)
                || activation.usuario == null
                || activation.usuario.status != UsuarioStatus.ATIVO
                || activation.usuario.password != null) {
            throw new TokenAtivacaoInvalidoException();
        }

        activation.usuario.password = BcryptUtil.bcryptHash(request.password());
        activation.usedAt = now;
    }

    private void validate(AtivarCredencialRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new TokenAtivacaoInvalidoException();
        }
        if (request.password() == null
                || request.password().isBlank()
                || request.password().length() < 12
                || request.password().length() > 128) {
            throw new SenhaAtivacaoInvalidaException();
        }
    }
}

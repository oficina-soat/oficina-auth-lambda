package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.domain.exceptions.CpfInvalidoException;
import br.com.oficina.autenticacao.domain.exceptions.CredenciaisObrigatoriasException;
import br.com.oficina.autenticacao.domain.exceptions.SenhaInvalidaException;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioInativoException;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioNaoEncontradoException;
import br.com.oficina.autenticacao.observability.AuthObservability;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @Inject
    AuthObservability authObservability = AuthObservability.noop();

    @Inject
    Tracer tracer = GlobalOpenTelemetry.getTracer("oficina-auth-lambda");

    @Transactional
    public AutenticarUsuarioResponse execute(AutenticarUsuarioRequest req) {
        authObservability.onAuthRequest();
        var timing = new AutenticacaoTiming();
        Span span = tracer.spanBuilder("auth.authenticate")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("deployment.environment", configured(System.getenv("DEPLOYMENT_ENVIRONMENT"), "lab"));
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
            String normalizedIssuer = normalizeIssuer(configured(issuer, DEFAULT_ISSUER));
            Set<String> grupos = usuarioEntity.papelEntities.stream()
                    .map(papelEntity -> papelEntity.nome)
                    .collect(Collectors.toSet());

            String accessToken = timing.jwt(() -> Jwt.issuer(normalizedIssuer)
                    .subject(usuarioEntity.documento())
                    .audience(configured(audience, DEFAULT_AUDIENCE))
                    .scope(configured(scope, DEFAULT_SCOPE))
                    .groups(grupos)
                    .issuedAt(now.getEpochSecond())
                    .expiresAt(now.plus(TOKEN_TTL).getEpochSecond())
                    .jws()
                    .keyId(configured(keyId, DEFAULT_KEY_ID))
                    .sign());

            authObservability.onAuthSuccess(timing);
            return new AutenticarUsuarioResponse(accessToken, "Bearer", (int) TOKEN_TTL.toSeconds());
        } catch (RuntimeException exception) {
            authObservability.onAuthFailure(classifyFailure(exception), timing, exception);
            span.setStatus(StatusCode.ERROR);
            span.recordException(exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    private static String configured(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalizeIssuer(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String classifyFailure(RuntimeException exception) {
        if (exception instanceof CredenciaisObrigatoriasException) {
            return "missing_credentials";
        }
        if (exception instanceof CpfInvalidoException) {
            return "invalid_cpf";
        }
        if (exception instanceof UsuarioNaoEncontradoException || exception instanceof SenhaInvalidaException) {
            return "invalid_credentials";
        }
        if (exception instanceof UsuarioInativoException) {
            return "inactive_user";
        }
        return "internal_error";
    }
}

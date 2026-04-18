package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AutenticarUsuarioUseCase;
import br.com.oficina.autenticacao.domain.UsuarioStatus;
import br.com.oficina.autenticacao.domain.exceptions.SenhaInvalidaException;
import br.com.oficina.autenticacao.persistence.PapelEntity;
import br.com.oficina.autenticacao.persistence.PessoaEntity;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class UsuarioLambdaResourceQuarkusTest {

    private static final String DOCUMENTO_ATIVO = "52998224725";
    private static final String SENHA_CORRETA = "secret";

    @Inject
    AutenticarUsuarioUseCase autenticarUsuarioUseCase;

    @Inject
    JWTParser jwtParser;

    @BeforeEach
    void setUp() {
        persistUsuarioIfMissing(DOCUMENTO_ATIVO, SENHA_CORRETA, "administrativo", "mecanico", "recepcionista");
    }

    @Test
    void shouldAuthenticateFromLambdaHandlerWithoutActiveRequestContext() throws Exception {
        UsuarioLambdaResource resource = new UsuarioLambdaResource(autenticarUsuarioUseCase);

        var response = invokeWithoutRequestContext(() -> resource.autenticar(
                new AutenticarUsuarioRequest(DOCUMENTO_ATIVO, SENHA_CORRETA),
                null));
        var token = jwtParser.parse(response.access_token());

        assertEquals("Bearer", response.token_type());
        assertEquals(3600, response.expires_in());
        assertEquals(DOCUMENTO_ATIVO, token.getSubject());
        assertEquals(Set.of("administrativo", "mecanico", "recepcionista"), token.getGroups());
    }

    @Test
    void shouldReturnBusinessFailureInsteadOfContextNotActiveWhenLambdaHandlerRunsWithoutRequestContext() {
        UsuarioLambdaResource resource = new UsuarioLambdaResource(autenticarUsuarioUseCase);

        Throwable thrown = assertThrows(
                Throwable.class,
                () -> invokeWithoutRequestContext(() -> resource.autenticar(
                        new AutenticarUsuarioRequest(DOCUMENTO_ATIVO, "senha-incorreta"),
                        null)));

        SenhaInvalidaException senhaInvalidaException = findInChain(thrown, SenhaInvalidaException.class);
        ContextNotActiveException contextNotActiveException = findInChain(thrown, ContextNotActiveException.class);

        assertNotNull(senhaInvalidaException);
        assertEquals("Senha inválida", senhaInvalidaException.motivo());
        assertNull(contextNotActiveException);
    }

    private static <T> T invokeWithoutRequestContext(Supplier<T> invocation) {
        var requestContext = Arc.container().requestContext();
        InjectableContext.ContextState previousState = requestContext.isActive() ? requestContext.getState() : null;

        if (requestContext.isActive()) {
            requestContext.deactivate();
        }

        try {
            assertFalse(requestContext.isActive());
            return invocation.get();
        } finally {
            if (previousState != null) {
                requestContext.activate(previousState);
            }
        }
    }

    private static void persistUsuarioIfMissing(String documento, String password, String... papeis) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (UsuarioEntity.findByDocumento(documento) != null) {
                return;
            }

            PessoaEntity pessoa = new PessoaEntity();
            pessoa.documento = documento;

            List<PapelEntity> papelEntities = PapelEntity.list("papel in ?1", List.of(papeis));
            if (papelEntities.size() != papeis.length) {
                throw new IllegalStateException("Nem todos os papeis esperados foram encontrados");
            }

            UsuarioEntity usuario = new UsuarioEntity();
            usuario.pessoa = pessoa;
            usuario.password = BcryptUtil.bcryptHash(password);
            usuario.status = UsuarioStatus.ATIVO;
            usuario.papelEntities.addAll(papelEntities);

            pessoa.persist();
            usuario.persist();
        });
    }

    private static <T extends Throwable> T findInChain(Throwable thrown, Class<T> expectedType) {
        Throwable current = thrown;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}

package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.domain.exceptions.CpfInvalidoException;
import br.com.oficina.autenticacao.domain.exceptions.CredenciaisObrigatoriasException;
import br.com.oficina.autenticacao.domain.exceptions.SenhaInvalidaException;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioInativoException;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioNaoEncontradoException;
import br.com.oficina.autenticacao.persistence.PapelEntity;
import br.com.oficina.autenticacao.persistence.PessoaEntity;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;
import io.smallrye.jwt.build.JwtSignatureBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutenticarUsuarioUseCaseTest {

    private final AutenticarUsuarioUseCase useCase = new AutenticarUsuarioUseCase();

    @Test
    void shouldRejectNullRequest() {
        CredenciaisObrigatoriasException exception = assertThrowsInChain(
                CredenciaisObrigatoriasException.class,
                () -> useCase.execute(null));

        assertEquals("cpf e password são obrigatórios", exception.getMessage());
    }

    @Test
    void shouldRejectBlankCpf() {
        CredenciaisObrigatoriasException exception = assertThrowsInChain(
                CredenciaisObrigatoriasException.class,
                () -> useCase.execute(new AutenticarUsuarioRequest("   ", "secret")));

        assertEquals("cpf e password são obrigatórios", exception.getMessage());
    }

    @Test
    void shouldRejectNullCpf() {
        CredenciaisObrigatoriasException exception = assertThrowsInChain(
                CredenciaisObrigatoriasException.class,
                () -> useCase.execute(new AutenticarUsuarioRequest(null, "secret")));

        assertEquals("cpf e password são obrigatórios", exception.getMessage());
    }

    @Test
    void shouldRejectNullPassword() {
        CredenciaisObrigatoriasException exception = assertThrowsInChain(
                CredenciaisObrigatoriasException.class,
                () -> useCase.execute(new AutenticarUsuarioRequest("84191404067", null)));

        assertEquals("cpf e password são obrigatórios", exception.getMessage());
    }

    @Test
    void shouldRejectBlankPassword() {
        CredenciaisObrigatoriasException exception = assertThrowsInChain(
                CredenciaisObrigatoriasException.class,
                () -> useCase.execute(new AutenticarUsuarioRequest("84191404067", "   ")));

        assertEquals("cpf e password são obrigatórios", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidCpfBeforeQueryingUser() {
        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class)) {
            CpfInvalidoException exception = assertThrowsInChain(
                    CpfInvalidoException.class,
                    () -> useCase.execute(new AutenticarUsuarioRequest("84191404066", "secret")));

            assertEquals("CPF inválido: 84191404066", exception.getMessage());
            panacheEntityBaseMock.verifyNoInteractions();
        }
    }

    @Test
    void shouldFailWhenUserDoesNotExist() {
        PanacheQuery<UsuarioEntity> query = mockPanacheQuery();

        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class)) {
            panacheEntityBaseMock.when(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067")).thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.empty());

            UsuarioNaoEncontradoException exception = assertThrowsInChain(
                    UsuarioNaoEncontradoException.class,
                    () -> useCase.execute(new AutenticarUsuarioRequest("84191404067", "secret")));

            assertEquals("Credenciais inválidas", exception.getMessage());
            assertEquals("Usuário não encontrado", exception.motivo());
        }
    }

    @Test
    void shouldFailWhenPasswordDoesNotMatch() {
        UsuarioEntity usuario = usuario("84191404067", BcryptUtil.bcryptHash("correct-secret"), "admin");
        PanacheQuery<UsuarioEntity> query = mockPanacheQuery();

        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class)) {
            panacheEntityBaseMock.when(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067")).thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.of(usuario));

            SenhaInvalidaException exception = assertThrowsInChain(
                    SenhaInvalidaException.class,
                    () -> useCase.execute(new AutenticarUsuarioRequest("84191404067", "wrong-secret")));

            assertEquals("Credenciais inválidas", exception.getMessage());
            assertEquals("Senha inválida", exception.motivo());
        }
    }

    @Test
    void shouldFailWhenUserIsInactive() {
        UsuarioEntity usuario = usuario("84191404067", BcryptUtil.bcryptHash("secret"), UsuarioStatus.INATIVO, "admin");
        PanacheQuery<UsuarioEntity> query = mockPanacheQuery();

        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class)) {
            panacheEntityBaseMock.when(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067")).thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.of(usuario));

            UsuarioInativoException exception = assertThrowsInChain(
                    UsuarioInativoException.class,
                    () -> useCase.execute(new AutenticarUsuarioRequest("84191404067", "secret")));

            assertEquals("Credenciais inválidas", exception.getMessage());
            assertEquals("Usuário inativo", exception.motivo());
        }
    }

    @Test
    void shouldNormalizeCpfBeforeSearchingUser() {
        UsuarioEntity usuario = usuario("84191404067", BcryptUtil.bcryptHash("secret"), "admin");
        PanacheQuery<UsuarioEntity> query = mockPanacheQuery();
        JwtClaimsBuilder jwtClaimsBuilder = mock(JwtClaimsBuilder.class, Mockito.CALLS_REAL_METHODS);

        JwtSignatureBuilder jwtSignatureBuilder = stubJwtBuilder(jwtClaimsBuilder);

        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<Jwt> jwtMock = Mockito.mockStatic(Jwt.class)) {
            panacheEntityBaseMock.when(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067")).thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.of(usuario));
            jwtMock.when(() -> Jwt.issuer("oficina-api")).thenReturn(jwtClaimsBuilder);

            AutenticarUsuarioResponse response =
                    useCase.execute(new AutenticarUsuarioRequest("841.914.040-67", "secret"));

            assertEquals("signed-token", response.access_token());
            verify(jwtSignatureBuilder).keyId("oficina-lab-rsa");
            panacheEntityBaseMock.verify(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067"));
        }
    }

    @Test
    void shouldReturnBearerTokenWhenCredentialsAreValid() {
        UsuarioEntity usuario = usuario("84191404067", BcryptUtil.bcryptHash("secret"), "admin");
        PanacheQuery<UsuarioEntity> query = mockPanacheQuery();
        JwtClaimsBuilder jwtClaimsBuilder = mock(JwtClaimsBuilder.class, Mockito.CALLS_REAL_METHODS);

        JwtSignatureBuilder jwtSignatureBuilder = stubJwtBuilder(jwtClaimsBuilder);

        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<Jwt> jwtMock = Mockito.mockStatic(Jwt.class)) {
            panacheEntityBaseMock.when(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067")).thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.of(usuario));
            jwtMock.when(() -> Jwt.issuer("oficina-api")).thenReturn(jwtClaimsBuilder);

            AutenticarUsuarioResponse response =
                    useCase.execute(new AutenticarUsuarioRequest("84191404067", "secret"));

            assertEquals("signed-token", response.access_token());
            assertEquals("Bearer", response.token_type());
            assertEquals(3600, response.expires_in());

            ArgumentCaptor<Long> issuedAtCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> expiresAtCaptor = ArgumentCaptor.forClass(Long.class);
            verify(jwtClaimsBuilder).subject("84191404067");
            verify(jwtClaimsBuilder).audience("oficina-app");
            verify(jwtClaimsBuilder).scope("oficina-app");
            verify(jwtClaimsBuilder).groups(Set.of("admin"));
            verify(jwtClaimsBuilder).issuedAt(issuedAtCaptor.capture());
            verify(jwtClaimsBuilder).expiresAt(expiresAtCaptor.capture());
            verify(jwtClaimsBuilder).jws();
            verify(jwtSignatureBuilder).keyId("oficina-lab-rsa");
            verify(jwtSignatureBuilder).sign();

            long ttlInSeconds = expiresAtCaptor.getValue() - issuedAtCaptor.getValue();
            assertTrue(ttlInSeconds >= 3600 && ttlInSeconds <= 3601,
                    "Token TTL should be approximately 3600 seconds");
        }
    }

    @Test
    void shouldIncludeAllRolesWhenUserHasMultipleRoles() {
        UsuarioEntity usuario = usuario("84191404067", BcryptUtil.bcryptHash("secret"), "admin", "mecanico");
        PanacheQuery<UsuarioEntity> query = mockPanacheQuery();
        JwtClaimsBuilder jwtClaimsBuilder = mock(JwtClaimsBuilder.class, Mockito.CALLS_REAL_METHODS);

        stubJwtBuilder(jwtClaimsBuilder);

        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<Jwt> jwtMock = Mockito.mockStatic(Jwt.class)) {
            panacheEntityBaseMock.when(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067")).thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.of(usuario));
            jwtMock.when(() -> Jwt.issuer("oficina-api")).thenReturn(jwtClaimsBuilder);

            AutenticarUsuarioResponse response =
                    useCase.execute(new AutenticarUsuarioRequest("84191404067", "secret"));

            assertEquals("signed-token", response.access_token());
            verify(jwtClaimsBuilder).groups(Set.of("admin", "mecanico"));
        }
    }

    @Test
    void shouldTrimTrailingSlashFromConfiguredIssuerBeforeSigningJwt() {
        UsuarioEntity usuario = usuario("84191404067", BcryptUtil.bcryptHash("secret"), "admin");
        PanacheQuery<UsuarioEntity> query = mockPanacheQuery();
        JwtClaimsBuilder jwtClaimsBuilder = mock(JwtClaimsBuilder.class, Mockito.CALLS_REAL_METHODS);

        useCase.issuer = "https://auth.oficina.example.com/";

        JwtSignatureBuilder jwtSignatureBuilder = stubJwtBuilder(jwtClaimsBuilder, "oficina-app", "oficina-app",
                "oficina-lab-rsa");

        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<Jwt> jwtMock = Mockito.mockStatic(Jwt.class)) {
            panacheEntityBaseMock.when(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067")).thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.of(usuario));
            jwtMock.when(() -> Jwt.issuer("https://auth.oficina.example.com")).thenReturn(jwtClaimsBuilder);

            AutenticarUsuarioResponse response =
                    useCase.execute(new AutenticarUsuarioRequest("84191404067", "secret"));

            assertEquals("signed-token", response.access_token());
            verify(jwtSignatureBuilder).keyId("oficina-lab-rsa");
        }
    }

    @Test
    void shouldUseConfiguredAudienceScopeAndKeyId() {
        UsuarioEntity usuario = usuario("84191404067", BcryptUtil.bcryptHash("secret"), "admin");
        PanacheQuery<UsuarioEntity> query = mockPanacheQuery();
        JwtClaimsBuilder jwtClaimsBuilder = mock(JwtClaimsBuilder.class, Mockito.CALLS_REAL_METHODS);

        useCase.issuer = "https://auth.oficina.example.com";
        useCase.audience = "oficina-backoffice";
        useCase.scope = "oficina-backoffice.read";
        useCase.keyId = "oficina-prod-rsa";

        JwtSignatureBuilder jwtSignatureBuilder = stubJwtBuilder(jwtClaimsBuilder, "oficina-backoffice",
                "oficina-backoffice.read", "oficina-prod-rsa");

        try (MockedStatic<PanacheEntityBase> panacheEntityBaseMock = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<Jwt> jwtMock = Mockito.mockStatic(Jwt.class)) {
            panacheEntityBaseMock.when(() -> PanacheEntityBase.find(UsuarioEntity.FIND_BY_DOCUMENTO_QUERY, "84191404067")).thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.of(usuario));
            jwtMock.when(() -> Jwt.issuer("https://auth.oficina.example.com")).thenReturn(jwtClaimsBuilder);

            AutenticarUsuarioResponse response =
                    useCase.execute(new AutenticarUsuarioRequest("84191404067", "secret"));

            assertEquals("signed-token", response.access_token());
            verify(jwtClaimsBuilder).audience("oficina-backoffice");
            verify(jwtClaimsBuilder).scope("oficina-backoffice.read");
            verify(jwtSignatureBuilder).keyId("oficina-prod-rsa");
        }
    }

    @SuppressWarnings("unchecked")
    private static PanacheQuery<UsuarioEntity> mockPanacheQuery() {
        return mock(PanacheQuery.class);
    }

    private static JwtSignatureBuilder stubJwtBuilder(JwtClaimsBuilder jwtClaimsBuilder) {
        return stubJwtBuilder(jwtClaimsBuilder, "oficina-app", "oficina-app", "oficina-lab-rsa");
    }

    private static JwtSignatureBuilder stubJwtBuilder(JwtClaimsBuilder jwtClaimsBuilder, String audience, String scope,
                                                      String keyId) {
        JwtSignatureBuilder jwtSignatureBuilder = mock(JwtSignatureBuilder.class);
        when(jwtClaimsBuilder.subject("84191404067")).thenReturn(jwtClaimsBuilder);
        when(jwtClaimsBuilder.audience(audience)).thenReturn(jwtClaimsBuilder);
        when(jwtClaimsBuilder.scope(scope)).thenReturn(jwtClaimsBuilder);
        when(jwtClaimsBuilder.groups(Mockito.anySet())).thenReturn(jwtClaimsBuilder);
        when(jwtClaimsBuilder.issuedAt(anyLong())).thenReturn(jwtClaimsBuilder);
        when(jwtClaimsBuilder.expiresAt(anyLong())).thenReturn(jwtClaimsBuilder);
        when(jwtClaimsBuilder.jws()).thenReturn(jwtSignatureBuilder);
        when(jwtSignatureBuilder.keyId(keyId)).thenReturn(jwtSignatureBuilder);
        when(jwtSignatureBuilder.sign()).thenReturn("signed-token");
        Mockito.clearInvocations(jwtClaimsBuilder, jwtSignatureBuilder);
        return jwtSignatureBuilder;
    }

    private static UsuarioEntity usuario(String documento, String password, String... roles) {
        return usuario(documento, password, UsuarioStatus.ATIVO, roles);
    }

    private static UsuarioEntity usuario(String documento, String password, UsuarioStatus status, String... roles) {
        PessoaEntity pessoa = new PessoaEntity();
        pessoa.documento = documento;

        UsuarioEntity usuario = new UsuarioEntity();
        usuario.pessoa = pessoa;
        usuario.password = password;
        usuario.status = status;
        for (String role : roles) {
            PapelEntity papel = new PapelEntity();
            papel.papel = role;
            usuario.papelEntities.add(papel);
        }
        return usuario;
    }

    private static <T extends Throwable> T assertThrowsInChain(Class<T> expectedType, Executable executable) {
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(Throwable.class, executable);
        Throwable current = thrown;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            current = current.getCause();
        }
        fail("Expected exception of type " + expectedType.getName() + " but got " + thrown);
        return assertInstanceOf(expectedType, thrown);
    }
}

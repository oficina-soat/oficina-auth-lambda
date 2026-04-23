package br.com.oficina.autenticacao.domain;

import br.com.oficina.autenticacao.domain.exceptions.CpfInvalidoException;
import br.com.oficina.autenticacao.domain.exceptions.SenhaInvalidaException;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioInativoException;
import br.com.oficina.autenticacao.persistence.PapelEntity;
import br.com.oficina.autenticacao.persistence.PessoaEntity;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AutenticarUsuarioUseCaseIT {

    private static final String DOCUMENTO_MULTIPLOS_PAPEIS = "52998224725";
    private static final String DOCUMENTO_CPF_FORMATADO = "57088799472";
    private static final String DOCUMENTO_SENHA_INVALIDA = "11144477735";
    private static final String DOCUMENTO_USUARIO_INATIVO = "39053344705";

    private final AutenticarUsuarioUseCase useCase = new AutenticarUsuarioUseCase();

    @Inject
    JWTParser jwtParser;

    @Test
    @TestTransaction
    void shouldAuthenticateUsingPessoaDocumentoAndReturnAllRoles() throws Exception {
        persistUsuario(DOCUMENTO_MULTIPLOS_PAPEIS, "secret", "administrativo", "mecanico");

        var response = useCase.execute(new AutenticarUsuarioRequest(DOCUMENTO_MULTIPLOS_PAPEIS, "secret"));
        var token = jwtParser.parse(response.access_token());

        assertEquals(DOCUMENTO_MULTIPLOS_PAPEIS, token.getSubject());
        assertEquals(Set.of("oficina-app"), token.getAudience());
        assertEquals(Set.of("administrativo", "mecanico"), token.getGroups());
        assertEquals("Bearer", response.token_type());
        assertEquals(3600, response.expires_in());
    }

    @Test
    @TestTransaction
    void shouldAuthenticateWithFormattedCpf() {
        persistUsuario(DOCUMENTO_CPF_FORMATADO, "secret", "administrativo");

        var response = useCase.execute(new AutenticarUsuarioRequest("570.887.994-72", "secret"));

        assertEquals("Bearer", response.token_type());
    }

    @Test
    @TestTransaction
    void shouldRejectInvalidPasswordForPersistedUser() {
        persistUsuario(DOCUMENTO_SENHA_INVALIDA, "correct-secret", "recepcionista");

        SenhaInvalidaException exception = assertThrows(
                SenhaInvalidaException.class,
                () -> useCase.execute(new AutenticarUsuarioRequest(DOCUMENTO_SENHA_INVALIDA, "wrong-secret")));

        assertEquals("Credenciais inválidas", exception.getMessage());
        assertEquals("Senha inválida", exception.motivo());
    }

    @Test
    @TestTransaction
    void shouldRejectInactiveUser() {
        persistUsuario(DOCUMENTO_USUARIO_INATIVO, "secret", UsuarioStatus.INATIVO, "recepcionista");

        UsuarioInativoException exception = assertThrows(
                UsuarioInativoException.class,
                () -> useCase.execute(new AutenticarUsuarioRequest(DOCUMENTO_USUARIO_INATIVO, "secret")));

        assertEquals("Credenciais inválidas", exception.getMessage());
        assertEquals("Usuário inativo", exception.motivo());
    }

    @Test
    @TestTransaction
    void shouldRejectInvalidCpfBeforeAuthentication() {
        CpfInvalidoException exception = assertThrows(
                CpfInvalidoException.class,
                () -> useCase.execute(new AutenticarUsuarioRequest("52998224724", "secret")));

        assertEquals("CPF inválido: 52998224724", exception.getMessage());
    }

    private static void persistUsuario(String documento, String password, String... papeis) {
        persistUsuario(documento, password, UsuarioStatus.ATIVO, papeis);
    }

    private static void persistUsuario(String documento, String password, UsuarioStatus status, String... papeis) {
        PessoaEntity pessoa = new PessoaEntity();
        pessoa.documento = documento;
        List<PapelEntity> papelEntities = PapelEntity.list("papel in ?1", List.of(papeis));

        if (papelEntities.size() != papeis.length) {
            throw new IllegalStateException("Nem todos os papeis esperados foram encontrados");
        }

        UsuarioEntity usuario = new UsuarioEntity();
        usuario.pessoa = pessoa;
        usuario.password = BcryptUtil.bcryptHash(password);
        usuario.status = status;
        usuario.papelEntities.addAll(papelEntities);

        pessoa.persist();
        usuario.persist();
    }
}

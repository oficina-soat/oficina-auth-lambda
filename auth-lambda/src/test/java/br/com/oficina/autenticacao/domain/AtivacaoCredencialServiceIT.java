package br.com.oficina.autenticacao.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.autenticacao.persistence.AtivacaoTokenEntity;
import br.com.oficina.autenticacao.persistence.PessoaEntity;
import br.com.oficina.autenticacao.persistence.UsuarioEntity;
import br.com.oficina.autenticacao.resource.dto.AtivacaoRequest;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AtivacaoCredencialServiceIT {
    private static final String PASSWORD = "senha-forte-123";

    @Inject AtivacaoCredencialService service;

    @Test
    @TestTransaction
    void deveAtivarCredencialUmaUnicaVezSemPersistirTokenAberto() {
        UUID usuarioId = UUID.randomUUID();
        UsuarioEntity usuario = persistirUsuarioSemCredencial(usuarioId);

        var response = service.solicitar(usuarioId);
        var tokenEntity = AtivacaoTokenEntity.<AtivacaoTokenEntity>findAll().firstResult();

        assertTrue(response.token().length() >= 43);
        assertNotEquals(response.token(), tokenEntity.tokenHash);
        assertEquals(64, tokenEntity.tokenHash.length());

        service.ativar(new AtivacaoRequest(response.token(), PASSWORD));

        assertTrue(BcryptUtil.matches(PASSWORD, usuario.password));
        assertTrue(tokenEntity.usedAt != null);
        assertThrows(
                BadRequestException.class,
                () -> service.ativar(new AtivacaoRequest(response.token(), PASSWORD)));
    }

    @Test
    @TestTransaction
    void deveInvalidarTokenAnteriorAoSolicitarOutro() {
        UUID usuarioId = UUID.randomUUID();
        persistirUsuarioSemCredencial(usuarioId);

        var primeiro = service.solicitar(usuarioId);
        var segundo = service.solicitar(usuarioId);

        assertNotEquals(primeiro.token(), segundo.token());
        assertThrows(
                BadRequestException.class,
                () -> service.ativar(new AtivacaoRequest(primeiro.token(), PASSWORD)));
        service.ativar(new AtivacaoRequest(segundo.token(), PASSWORD));
    }

    private UsuarioEntity persistirUsuarioSemCredencial(UUID externalId) {
        var pessoa = new PessoaEntity();
        pessoa.documento = "52998224725";
        pessoa.tipoPessoa = TipoPessoa.FISICA;
        pessoa.nome = "Usuario Ativacao";
        pessoa.persist();

        var usuario = new UsuarioEntity();
        usuario.externalId = externalId;
        usuario.pessoa = pessoa;
        usuario.status = UsuarioStatus.ATIVO;
        usuario.password = null;
        usuario.persist();
        return usuario;
    }
}

package br.com.oficina.autenticacao.persistence;

import br.com.oficina.autenticacao.domain.TipoPessoa;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UsuarioEntityTest {
    @Test
    void deveRetornarDocumentoQuandoPessoaExistir() {
        PessoaEntity pessoa = new PessoaEntity();
        pessoa.documento = "84191404067";
        pessoa.tipoPessoa = TipoPessoa.FISICA;

        UsuarioEntity usuario = new UsuarioEntity();
        usuario.pessoa = pessoa;

        Assertions.assertEquals("84191404067", usuario.documento());
    }

    @Test
    void deveRetornarNuloQuandoPessoaNaoExistir() {
        UsuarioEntity usuario = new UsuarioEntity();

        Assertions.assertNull(usuario.documento());
    }
}

package br.com.oficina.autenticacao.persistence;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UsuarioEntityTest {
    @Test
    void deveRetornarDocumentoQuandoPessoaExistir() {
        PessoaEntity pessoa = new PessoaEntity();
        pessoa.documento = "84191404067";

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

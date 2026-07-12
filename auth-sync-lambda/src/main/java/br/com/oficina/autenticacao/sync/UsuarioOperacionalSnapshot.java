package br.com.oficina.autenticacao.sync;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record UsuarioOperacionalSnapshot(
        UUID usuarioId,
        UUID pessoaId,
        String nome,
        String documento,
        String status,
        List<String> papeis,
        OffsetDateTime atualizadoEm) {
}

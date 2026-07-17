package br.com.oficina.autenticacao.resource.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DashboardCredenciaisResponse(
        OffsetDateTime generatedAt,
        OffsetDateTime dataAsOf,
        int refreshAfterSeconds,
        List<ContagemCredencial> contagensPorStatus,
        List<CredencialAtencao> atencoes) {

    public record ContagemCredencial(String status, long quantidade) {
    }

    public record CredencialAtencao(
            UUID usuarioId,
            String status,
            OffsetDateTime atualizadoEm,
            OffsetDateTime expiresAt,
            List<String> acoesPermitidas) {
    }
}

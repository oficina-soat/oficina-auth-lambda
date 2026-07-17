package br.com.oficina.autenticacao.resource.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CredencialStatusResponse(
        String status,
        OffsetDateTime expiresAt,
        List<String> acoesPermitidas) {
}

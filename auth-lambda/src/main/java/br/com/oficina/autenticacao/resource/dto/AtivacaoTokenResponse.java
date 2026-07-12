package br.com.oficina.autenticacao.resource.dto;

import java.time.OffsetDateTime;

public record AtivacaoTokenResponse(String token, OffsetDateTime expiresAt) {
}

package br.com.oficina.autenticacao.resource.dto;

public record AutenticarUsuarioResponse(
        String access_token,
        String token_type,
        int expires_in) {
}

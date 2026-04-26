package br.com.oficina.notificacao.resource.dto;

public record EnviarEmailRequest(String emailDestino, String assunto, String conteudo) {
}

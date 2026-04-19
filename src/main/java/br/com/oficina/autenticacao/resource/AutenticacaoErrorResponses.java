package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AutenticacaoComMotivo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;

final class AutenticacaoErrorResponses {

    private AutenticacaoErrorResponses() {
    }

    static Response status(Response.Status status, Throwable throwable, AutenticacaoComMotivo motivo) {
        var body = new LinkedHashMap<String, String>();
        body.put("message", throwable.getMessage());
        body.put("motivo", motivo.motivo());

        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}

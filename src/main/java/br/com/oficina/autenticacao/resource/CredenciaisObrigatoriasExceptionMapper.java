package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.exceptions.CredenciaisObrigatoriasException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CredenciaisObrigatoriasExceptionMapper implements ExceptionMapper<CredenciaisObrigatoriasException> {

    @Override
    public Response toResponse(CredenciaisObrigatoriasException exception) {
        return AutenticacaoErrorResponses.status(Response.Status.BAD_REQUEST, exception, exception);
    }
}

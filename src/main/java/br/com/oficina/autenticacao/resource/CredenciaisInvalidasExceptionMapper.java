package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.exceptions.CredenciaisInvalidasException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CredenciaisInvalidasExceptionMapper implements ExceptionMapper<CredenciaisInvalidasException> {

    @Override
    public Response toResponse(CredenciaisInvalidasException exception) {
        return AutenticacaoErrorResponses.status(Response.Status.UNAUTHORIZED, exception, exception);
    }
}

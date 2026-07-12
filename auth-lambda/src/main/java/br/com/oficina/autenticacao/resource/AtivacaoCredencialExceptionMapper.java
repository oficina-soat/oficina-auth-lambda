package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.exceptions.AtivacaoCredencialException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AtivacaoCredencialExceptionMapper implements ExceptionMapper<AtivacaoCredencialException> {

    @Override
    public Response toResponse(AtivacaoCredencialException exception) {
        return AutenticacaoErrorResponses.status(exception.status(), exception, exception);
    }
}

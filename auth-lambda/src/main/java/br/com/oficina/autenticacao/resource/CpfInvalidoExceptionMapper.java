package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.exceptions.CpfInvalidoException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CpfInvalidoExceptionMapper implements ExceptionMapper<CpfInvalidoException> {

    @Override
    public Response toResponse(CpfInvalidoException exception) {
        return AutenticacaoErrorResponses.status(Response.Status.BAD_REQUEST, exception, exception);
    }
}

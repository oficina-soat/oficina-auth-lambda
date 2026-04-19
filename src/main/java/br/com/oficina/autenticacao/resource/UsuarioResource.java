package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AutenticarUsuarioUseCase;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UsuarioResource {

    @Inject AutenticarUsuarioUseCase autenticarUsuarioUseCase;

    UsuarioResource(AutenticarUsuarioUseCase autenticarUsuarioUseCase) {
        this.autenticarUsuarioUseCase = autenticarUsuarioUseCase;
    }

    @POST
    public AutenticarUsuarioResponse autenticar(AutenticarUsuarioRequest request) {
        return autenticarUsuarioUseCase.execute(request);
    }
}

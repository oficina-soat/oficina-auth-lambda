package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AutenticarUsuarioUseCase;
import br.com.oficina.autenticacao.domain.AtivacaoCredencialService;
import br.com.oficina.autenticacao.domain.DashboardCredenciaisService;
import br.com.oficina.autenticacao.resource.dto.AtivacaoRequest;
import br.com.oficina.autenticacao.resource.dto.AtivacaoTokenResponse;
import br.com.oficina.autenticacao.resource.dto.CredencialStatusResponse;
import br.com.oficina.autenticacao.resource.dto.DashboardCredenciaisResponse;
import jakarta.annotation.security.RolesAllowed;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UsuarioResource {

    @Inject AutenticarUsuarioUseCase autenticarUsuarioUseCase;
    @Inject AtivacaoCredencialService ativacaoCredencialService;
    @Inject DashboardCredenciaisService dashboardCredenciaisService;

    UsuarioResource(AutenticarUsuarioUseCase autenticarUsuarioUseCase) {
        this.autenticarUsuarioUseCase = autenticarUsuarioUseCase;
    }

    @POST
    public AutenticarUsuarioResponse autenticar(AutenticarUsuarioRequest request) {
        return autenticarUsuarioUseCase.execute(request);
    }

    @POST
    @Path("/token")
    public AutenticarUsuarioResponse token(AutenticarUsuarioRequest request) {
        return autenticar(request);
    }

    @POST
    @Path("/usuarios/{usuarioId}/ativacao")
    @RolesAllowed("administrativo")
    public RestResponse<AtivacaoTokenResponse> solicitarAtivacao(@PathParam("usuarioId") UUID usuarioId) {
        AtivacaoTokenResponse response = ativacaoCredencialService.solicitar(usuarioId);
        return RestResponse.status(Response.Status.CREATED, response);
    }

    @GET
    @Path("/usuarios/{usuarioId}/credencial")
    @RolesAllowed("administrativo")
    public CredencialStatusResponse consultarCredencial(@PathParam("usuarioId") UUID usuarioId) {
        return ativacaoCredencialService.consultar(usuarioId);
    }

    @GET
    @Path("/dashboard/credenciais")
    @RolesAllowed("administrativo")
    public DashboardCredenciaisResponse consultarDashboardCredenciais() {
        return dashboardCredenciaisService.consultar();
    }

    @POST
    @Path("/ativacoes")
    public Response ativar(AtivacaoRequest request) {
        ativacaoCredencialService.ativar(request);
        return Response.noContent().build();
    }
}

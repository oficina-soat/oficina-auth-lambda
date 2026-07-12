package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AtivarCredencialUseCase;
import br.com.oficina.autenticacao.domain.SolicitarAtivacaoCredencialUseCase;
import br.com.oficina.autenticacao.domain.TipoDePapelValues;
import br.com.oficina.autenticacao.resource.dto.AtivarCredencialRequest;
import io.smallrye.common.annotation.Blocking;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.UUID;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class AtivacaoCredencialResource {
    private final SolicitarAtivacaoCredencialUseCase solicitarAtivacao;
    private final AtivarCredencialUseCase ativarCredencial;
    private final SecurityIdentity securityIdentity;

    @Inject
    AtivacaoCredencialResource(
            SolicitarAtivacaoCredencialUseCase solicitarAtivacao,
            AtivarCredencialUseCase ativarCredencial,
            SecurityIdentity securityIdentity) {
        this.solicitarAtivacao = solicitarAtivacao;
        this.ativarCredencial = ativarCredencial;
        this.securityIdentity = securityIdentity;
    }

    @POST
    @Path("/usuarios/{usuarioId}/ativacao")
    @RolesAllowed(TipoDePapelValues.ADMINISTRATIVO)
    public Response solicitar(@PathParam("usuarioId") UUID usuarioId) {
        var response = solicitarAtivacao.execute(usuarioId, securityIdentity.getPrincipal().getName());
        return Response.created(URI.create("/auth/usuarios/" + usuarioId + "/ativacao"))
                .entity(response)
                .build();
    }

    @POST
    @Path("/ativacoes")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    public Response ativar(AtivarCredencialRequest request) {
        ativarCredencial.execute(request);
        return Response.noContent().build();
    }
}

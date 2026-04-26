package br.com.oficina.notificacao.resource;

import br.com.oficina.notificacao.domain.EnviarEmailUseCase;
import br.com.oficina.notificacao.resource.dto.EnviarEmailRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/notificacoes")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class NotificacaoResource {

    @Inject EnviarEmailUseCase enviarEmailUseCase;

    NotificacaoResource(EnviarEmailUseCase enviarEmailUseCase) {
        this.enviarEmailUseCase = enviarEmailUseCase;
    }

    @POST
    @Path("/email")
    public Response enviarEmail(EnviarEmailRequest request) {
        enviarEmailUseCase.execute(request == null
                ? null
                : new EnviarEmailUseCase.Command(
                        request.emailDestino(),
                        request.assunto(),
                        request.conteudo()));
        return Response.noContent().build();
    }
}

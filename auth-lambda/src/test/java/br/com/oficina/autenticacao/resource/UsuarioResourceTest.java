package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AutenticarUsuarioUseCase;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertSame;

class UsuarioResourceTest {

    @Test
    void shouldReturnResponseFromAuthentication() {
        AutenticarUsuarioRequest request = new AutenticarUsuarioRequest("84191404067", "secret");
        AutenticarUsuarioResponse expected = new AutenticarUsuarioResponse("token", "Bearer", 3600);
        AutenticarUsuarioUseCase useCase = Mockito.mock(AutenticarUsuarioUseCase.class);
        UsuarioResource resource = new UsuarioResource(useCase);

        Mockito.doReturn(expected).when(useCase).execute(request);

        AutenticarUsuarioResponse response = resource.autenticar(request);

        assertSame(expected, response);
        Mockito.verify(useCase).execute(request);
    }
}

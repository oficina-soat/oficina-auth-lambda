package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AutenticacaoComMotivo;
import br.com.oficina.autenticacao.domain.AutenticarUsuarioUseCase;
import br.com.oficina.autenticacao.domain.exceptions.CpfInvalidoException;
import br.com.oficina.autenticacao.domain.exceptions.CredenciaisObrigatoriasException;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class UsuarioLambdaResource implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, String> JSON_HEADERS = Map.of("content-type", "application/json");

    @Inject AutenticarUsuarioUseCase autenticarUsuarioUseCase;

    UsuarioLambdaResource(AutenticarUsuarioUseCase autenticarUsuarioUseCase) {
        this.autenticarUsuarioUseCase = autenticarUsuarioUseCase;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        try {
            return jsonResponse(200, autenticar(readRequest(input), context));
        } catch (Throwable throwable) {
            return errorResponse(throwable);
        }
    }

    AutenticarUsuarioResponse autenticar(AutenticarUsuarioRequest input, Context context) {
        try {
            return autenticarUsuarioUseCase.execute(input);
        } catch (Throwable throwable) {
            throw propagateFailure(throwable, context);
        }
    }

    private static AutenticarUsuarioRequest readRequest(APIGatewayV2HTTPEvent event) throws JsonProcessingException {
        if (event == null || event.getBody() == null || event.getBody().isBlank()) {
            return new AutenticarUsuarioRequest(null, null);
        }

        var body = event.getBody();
        if (event.getIsBase64Encoded()) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }

        return OBJECT_MAPPER.readValue(body, AutenticarUsuarioRequest.class);
    }

    private static APIGatewayV2HTTPResponse jsonResponse(int statusCode, Object body) throws JsonProcessingException {
        var response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(statusCode);
        response.setHeaders(JSON_HEADERS);
        response.setBody(OBJECT_MAPPER.writeValueAsString(body));
        return response;
    }

    private static APIGatewayV2HTTPResponse errorResponse(Throwable throwable) {
        try {
            var statusCode = statusCode(throwable);
            var motivo = authenticationFailure(throwable);
            var message = statusCode == 500 ? "Erro interno" : throwable.getMessage();
            var body = new LinkedHashMap<String, String>();
            body.put("message", message);
            if (motivo != null) {
                body.put("motivo", motivo.motivo());
            }
            return jsonResponse(statusCode, body);
        } catch (JsonProcessingException jsonFailure) {
            throw new RuntimeException(jsonFailure);
        }
    }

    private static int statusCode(Throwable throwable) {
        if (findInChain(throwable, JsonProcessingException.class) != null
                || findInChain(throwable, CpfInvalidoException.class) != null
                || findInChain(throwable, CredenciaisObrigatoriasException.class) != null) {
            return 400;
        }

        if (authenticationFailure(throwable) != null) {
            return 401;
        }

        return 500;
    }

    private RuntimeException propagateFailure(Throwable throwable, Context context) {
        var requestId = requestId(context);
        AutenticacaoComMotivo falhaDeAutenticacao = authenticationFailure(throwable);

        if (falhaDeAutenticacao != null) {
            Log.warn("Autenticacao nao concluida. requestId=" + requestId
                    + " motivo=" + falhaDeAutenticacao.motivo());
        } else {
            Log.error("Falha inesperada ao autenticar usuario. requestId=" + requestId, throwable);
        }

        return asRuntimeException(throwable);
    }

    private static AutenticacaoComMotivo authenticationFailure(Throwable throwable) {
        return findInChain(throwable, AutenticacaoComMotivo.class);
    }

    private static <T> T findInChain(Throwable throwable, Class<T> expectedType) {
        var current = throwable;

        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            current = current.getCause();
        }

        return null;
    }

    private static RuntimeException asRuntimeException(Throwable throwable) {
        return throwable instanceof RuntimeException runtimeException
                ? runtimeException
                : new RuntimeException(throwable);
    }

    private static String requestId(Context context) {
        String awsRequestId = context == null ? null : context.getAwsRequestId();
        return awsRequestId == null || awsRequestId.isBlank() ? "N/A" : awsRequestId;
    }
}

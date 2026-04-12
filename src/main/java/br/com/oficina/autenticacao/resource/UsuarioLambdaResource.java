package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AutenticacaoComMotivo;
import br.com.oficina.autenticacao.domain.AutenticarUsuarioUseCase;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;

public class UsuarioLambdaResource implements RequestHandler<AutenticarUsuarioRequest, AutenticarUsuarioResponse> {

    @Inject AutenticarUsuarioUseCase autenticarUsuarioUseCase;

    UsuarioLambdaResource(AutenticarUsuarioUseCase autenticarUsuarioUseCase) {
        this.autenticarUsuarioUseCase = autenticarUsuarioUseCase;
    }

    @Override
    public AutenticarUsuarioResponse handleRequest(AutenticarUsuarioRequest input, Context context) {
        try {
            return autenticarUsuarioUseCase.execute(input);
        } catch (Throwable throwable) {
            throw propagateFailure(throwable, context);
        }
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
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof AutenticacaoComMotivo autenticacaoComMotivo) {
                return autenticacaoComMotivo;
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

package br.com.oficina.autenticacao.resource;

import br.com.oficina.autenticacao.domain.AutenticarUsuarioUseCase;
import br.com.oficina.autenticacao.domain.exceptions.UsuarioInativoException;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioRequest;
import br.com.oficina.autenticacao.resource.dto.AutenticarUsuarioResponse;
import com.amazonaws.services.lambda.runtime.Context;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

class UsuarioLambdaResourceTest {

    @Test
    void shouldReturnResponseFromHandleRequest() {
        AutenticarUsuarioRequest request = new AutenticarUsuarioRequest("84191404067", "secret");
        AutenticarUsuarioResponse expected = new AutenticarUsuarioResponse("token", "Bearer", 3600);
        AutenticarUsuarioUseCase useCase = Mockito.mock(AutenticarUsuarioUseCase.class);
        UsuarioLambdaResource resource = new UsuarioLambdaResource(useCase);

        Mockito.doReturn(expected).when(useCase).execute(request);

        AutenticarUsuarioResponse response = resource.handleRequest(request, null);

        assertSame(expected, response);
        Mockito.verify(useCase).execute(request);
    }

    @Test
    void shouldRethrowRuntimeExceptionFromHandleRequest() {
        AutenticarUsuarioUseCase useCase = Mockito.mock(AutenticarUsuarioUseCase.class);
        UsuarioLambdaResource resource = new UsuarioLambdaResource(useCase);
        RuntimeException failure = new IllegalStateException("boom");

        try (LogCapture capture = new LogCapture()) {
            Mockito.doThrow(failure)
                    .when(useCase)
                    .execute(Mockito.any());

            RuntimeException exception = assertThrowsInChain(
                    RuntimeException.class,
                    () -> resource.handleRequest(new AutenticarUsuarioRequest("84191404067", "secret"), null));

            assertSame(failure, exception);
            assertEquals(List.of("Falha inesperada ao autenticar usuario. requestId=N/A"), capture.messages(Level.SEVERE));
            assertSame(failure, capture.throwables(Level.SEVERE).getFirst());
        }
    }

    @Test
    void shouldWrapCheckedExceptionFromHandleRequest() {
        AutenticarUsuarioUseCase useCase = Mockito.mock(AutenticarUsuarioUseCase.class);
        UsuarioLambdaResource resource = new UsuarioLambdaResource(useCase);
        IOException failure = new IOException("io failure");

        try (LogCapture capture = new LogCapture()) {
            Mockito.doAnswer(invocation -> {
                throw failure;
            }).when(useCase).execute(Mockito.any());

            RuntimeException exception = assertThrowsInChain(
                    RuntimeException.class,
                    () -> resource.handleRequest(new AutenticarUsuarioRequest("84191404067", "secret"), null));

            assertSame(failure, exception.getCause());
            assertEquals(List.of("Falha inesperada ao autenticar usuario. requestId=N/A"), capture.messages(Level.SEVERE));
            assertSame(failure, capture.throwables(Level.SEVERE).getFirst());
        }
    }

    @Test
    void shouldLogAuthenticationReasonFromHandleRequest() {
        AutenticarUsuarioUseCase useCase = Mockito.mock(AutenticarUsuarioUseCase.class);
        UsuarioLambdaResource resource = new UsuarioLambdaResource(useCase);
        UsuarioInativoException failure = new UsuarioInativoException();
        Context context = Mockito.mock(Context.class);

        Mockito.doReturn("req-123").when(context).getAwsRequestId();

        try (LogCapture capture = new LogCapture()) {
            Mockito.doThrow(failure)
                    .when(useCase)
                    .execute(Mockito.any());

            UsuarioInativoException exception = assertThrowsInChain(
                    UsuarioInativoException.class,
                    () -> resource.handleRequest(new AutenticarUsuarioRequest("84191404067", "secret"), context));

            assertSame(failure, exception);
            assertEquals(List.of("Autenticacao nao concluida. requestId=req-123 motivo=Usuário inativo"),
                    capture.messages(Level.WARNING));
        }
    }

    private static <T extends Throwable> T assertThrowsInChain(Class<T> expectedType, org.junit.jupiter.api.function.Executable executable) {
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(Throwable.class, executable);
        Throwable current = thrown;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            current = current.getCause();
        }
        fail("Expected exception of type " + expectedType.getName() + " but got " + thrown);
        return assertInstanceOf(expectedType, thrown);
    }

    private static final class LogCapture extends ExtHandler implements AutoCloseable {
        private final org.jboss.logmanager.Logger logger =
                org.jboss.logmanager.Logger.getLogger(UsuarioLambdaResource.class.getName());
        private final List<ExtLogRecord> records = new ArrayList<>();

        private LogCapture() {
            logger.addHandler(this);
        }

        @Override
        protected void doPublish(ExtLogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }

        private List<String> messages(Level level) {
            return records.stream()
                    .filter(record -> level.equals(record.getLevel()))
                    .map(ExtLogRecord::getFormattedMessage)
                    .toList();
        }

        private List<Throwable> throwables(Level level) {
            return records.stream()
                    .filter(record -> level.equals(record.getLevel()))
                    .map(ExtLogRecord::getThrown)
                    .toList();
        }
    }
}

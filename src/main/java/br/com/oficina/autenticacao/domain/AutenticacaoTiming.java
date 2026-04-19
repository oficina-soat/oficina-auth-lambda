package br.com.oficina.autenticacao.domain;

import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class AutenticacaoTiming {
    private static final Logger LOG = Logger.getLogger(AutenticacaoTiming.class);

    private final long startedAt = System.nanoTime();
    private long cpfMs;
    private long dbMs;
    private long bcryptMs;
    private long jwtMs;

    <T> T cpf(Supplier<T> step) {
        return measure(step, elapsed -> cpfMs = elapsed);
    }

    <T> T db(Supplier<T> step) {
        return measure(step, elapsed -> dbMs = elapsed);
    }

    <T> T bcrypt(Supplier<T> step) {
        return measure(step, elapsed -> bcryptMs = elapsed);
    }

    <T> T jwt(Supplier<T> step) {
        return measure(step, elapsed -> jwtMs = elapsed);
    }

    void success() {
        log("success", "-");
    }

    void failure(RuntimeException exception) {
        log("failure", exception.getClass().getSimpleName());
    }

    private <T> T measure(Supplier<T> step, StepElapsed elapsedConsumer) {
        long stepStartedAt = System.nanoTime();
        try {
            return step.get();
        } finally {
            elapsedConsumer.accept(elapsedMillis(stepStartedAt));
        }
    }

    private void log(String outcome, String reason) {
        if (!LOG.isInfoEnabled()) {
            return;
        }

        LOG.infof("auth timing outcome=%s reason=%s totalMs=%d cpfMs=%d dbMs=%d bcryptMs=%d jwtMs=%d",
                outcome,
                reason,
                elapsedMillis(startedAt),
                cpfMs,
                dbMs,
                bcryptMs,
                jwtMs);
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    @FunctionalInterface
    private interface StepElapsed {
        void accept(long elapsed);
    }
}

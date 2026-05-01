package br.com.oficina.autenticacao.domain;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class AutenticacaoTiming {

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

    public long totalMs() {
        return elapsedMillis(startedAt);
    }

    public long cpfMs() {
        return cpfMs;
    }

    public long dbMs() {
        return dbMs;
    }

    public long bcryptMs() {
        return bcryptMs;
    }

    public long jwtMs() {
        return jwtMs;
    }

    private <T> T measure(Supplier<T> step, StepElapsed elapsedConsumer) {
        long stepStartedAt = System.nanoTime();
        try {
            return step.get();
        } finally {
            elapsedConsumer.accept(elapsedMillis(stepStartedAt));
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    @FunctionalInterface
    private interface StepElapsed {
        void accept(long elapsed);
    }
}

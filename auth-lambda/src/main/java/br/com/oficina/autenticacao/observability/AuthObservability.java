package br.com.oficina.autenticacao.observability;

import br.com.oficina.autenticacao.domain.AutenticacaoTiming;

public interface AuthObservability {

    void onAuthRequest();

    void onAuthSuccess(AutenticacaoTiming timing);

    void onAuthFailure(String failureType, AutenticacaoTiming timing, Throwable throwable);

    static AuthObservability noop() {
        return NoopAuthObservability.INSTANCE;
    }

    enum NoopAuthObservability implements AuthObservability {
        INSTANCE;

        @Override
        public void onAuthRequest() {
        }

        @Override
        public void onAuthSuccess(AutenticacaoTiming timing) {
        }

        @Override
        public void onAuthFailure(String failureType, AutenticacaoTiming timing, Throwable throwable) {
        }
    }
}

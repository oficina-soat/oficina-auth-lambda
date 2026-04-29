package br.com.oficina.autenticacao.observability;

import br.com.oficina.autenticacao.domain.AutenticacaoTiming;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class QuarkusAuthObservability implements AuthObservability {

    private static final Logger LOG = Logger.getLogger(QuarkusAuthObservability.class);

    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final boolean metricsEnabled;
    private final boolean tracingEnabled;
    private final String serviceName;
    private final String deploymentEnvironment;

    public QuarkusAuthObservability(
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "oficina.observability.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "oficina.observability.metrics.enabled", defaultValue = "true") boolean metricsEnabled,
            @ConfigProperty(name = "oficina.observability.tracing.enabled", defaultValue = "true") boolean tracingEnabled,
            @ConfigProperty(name = "quarkus.application.name", defaultValue = "oficina-auth-lambda") String serviceName,
            @ConfigProperty(name = "oficina.observability.deployment-environment", defaultValue = "lab") String deploymentEnvironment) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.metricsEnabled = metricsEnabled;
        this.tracingEnabled = tracingEnabled;
        this.serviceName = serviceName;
        this.deploymentEnvironment = deploymentEnvironment;
    }

    @Override
    public void onAuthRequest() {
        if (!enabled) {
            return;
        }

        recordCounter("auth_requests_total", Tags.of(
                "service", serviceName,
                "env", deploymentEnvironment));
    }

    @Override
    public void onAuthSuccess(AutenticacaoTiming timing) {
        if (!enabled) {
            return;
        }

        recordLatency("success", timing.totalMs());
        annotateCurrentSpan(Map.of(
                "auth.outcome", "success",
                "auth.total_ms", Long.toString(timing.totalMs()),
                "auth.db_ms", Long.toString(timing.dbMs()),
                "auth.bcrypt_ms", Long.toString(timing.bcryptMs()),
                "auth.jwt_ms", Long.toString(timing.jwtMs())));
    }

    @Override
    public void onAuthFailure(String failureType, AutenticacaoTiming timing, Throwable throwable) {
        if (!enabled) {
            return;
        }

        recordLatency("failure", timing.totalMs());
        recordCounter("auth_failures_total", Tags.of(
                "service", serviceName,
                "env", deploymentEnvironment,
                "failure_type", failureType));

        var span = Span.current();
        if (tracingEnabled) {
            span.setAttribute("auth.outcome", "failure");
            span.setAttribute("auth.failure_type", failureType);
            span.recordException(throwable);
            span.setStatus(StatusCode.ERROR);
        }

        var fields = new LinkedHashMap<String, Object>();
        fields.putAll(ObservabilityMdcScope.currentTraceContextFields());
        fields.put("error.type", throwable.getClass().getSimpleName());
        fields.put("error.message", throwable.getMessage());
        fields.put("auth.failure_type", failureType);
        try (var ignored = ObservabilityMdcScope.with(fields)) {
            LOG.warn("Falha no fluxo de autenticacao");
        }
    }

    private void recordCounter(String name, Tags tags) {
        if (!metricsEnabled) {
            return;
        }
        Counter.builder(name).tags(tags).register(meterRegistry).increment();
    }

    private void recordLatency(String outcome, long latencyMs) {
        if (!metricsEnabled) {
            return;
        }
        DistributionSummary.builder("auth_latency_ms")
                .baseUnit("milliseconds")
                .tags(Tags.of(
                        "service", serviceName,
                        "env", deploymentEnvironment,
                        "outcome", outcome))
                .register(meterRegistry)
                .record(latencyMs);
    }

    private void annotateCurrentSpan(Map<String, String> attributes) {
        if (!tracingEnabled) {
            return;
        }
        Span span = Span.current();
        attributes.forEach(span::setAttribute);
    }
}

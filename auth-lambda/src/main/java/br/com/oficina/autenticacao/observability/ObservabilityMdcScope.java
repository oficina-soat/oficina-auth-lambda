package br.com.oficina.autenticacao.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.jboss.logmanager.MDC;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ObservabilityMdcScope implements AutoCloseable {

    private final Map<String, String> previousValues = new HashMap<>();

    private ObservabilityMdcScope(Map<String, ?> fields) {
        fields.forEach(this::put);
    }

    public static ObservabilityMdcScope with(Map<String, ?> fields) {
        return new ObservabilityMdcScope(fields);
    }

    public static Map<String, Object> currentTraceContextFields() {
        var fields = new LinkedHashMap<String, Object>();
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            fields.put("trace_id", spanContext.getTraceId());
            fields.put("span_id", spanContext.getSpanId());
        }
        return fields;
    }

    private void put(String key, Object value) {
        previousValues.putIfAbsent(key, MDC.get(key));
        if (value == null) {
            MDC.remove(key);
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, Objects.toString(value));
    }

    @Override
    public void close() {
        previousValues.forEach((key, previousValue) -> {
            if (previousValue == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previousValue);
            }
        });
    }
}

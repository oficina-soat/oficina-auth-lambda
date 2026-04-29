package br.com.oficina.autenticacao.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;

import java.io.IOException;
import java.util.UUID;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class RequestCorrelationFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(RequestCorrelationFilter.class);
    private static final String REQUEST_ID_PROPERTY = "oficina.request_id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Context
    ResourceInfo resourceInfo;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "oficina-auth-lambda")
    String serviceName;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String serviceVersion;

    @ConfigProperty(name = "oficina.observability.deployment-environment", defaultValue = "lab")
    String deploymentEnvironment;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String requestId = firstNonBlank(
                requestContext.getHeaderString(REQUEST_ID_HEADER),
                requestContext.getHeaderString("X-Correlation-Id"),
                UUID.randomUUID().toString());

        requestContext.setProperty(REQUEST_ID_PROPERTY, requestId);
        requestContext.getHeaders().putSingle(REQUEST_ID_HEADER, requestId);

        MDC.put("request_id", requestId);
        MDC.put("http.method", requestContext.getMethod());
        MDC.put("url.path", "/" + requestContext.getUriInfo().getPath());
        MDC.put("client.address", resolveClientAddress(requestContext));
        MDC.put("service.name", serviceName);
        MDC.put("service.namespace", "oficina");
        MDC.put("service.version", serviceVersion);
        MDC.put("deployment.environment", deploymentEnvironment);
        putTraceContext();
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        String requestId = (String) requestContext.getProperty(REQUEST_ID_PROPERTY);
        responseContext.getHeaders().putSingle(REQUEST_ID_HEADER, requestId);

        MDC.put("request_id", requestId);
        MDC.put("http.method", requestContext.getMethod());
        MDC.put("http.route", resolveRoute(requestContext));
        MDC.put("url.path", "/" + requestContext.getUriInfo().getPath());
        MDC.put("http.status_code", Integer.toString(responseContext.getStatus()));
        MDC.put("client.address", resolveClientAddress(requestContext));
        MDC.put("service.name", serviceName);
        MDC.put("service.namespace", "oficina");
        MDC.put("service.version", serviceVersion);
        MDC.put("deployment.environment", deploymentEnvironment);
        putTraceContext();

        try {
            LOG.info("HTTP request completed");
        } finally {
            clearMdc();
        }
    }

    private static void putTraceContext() {
        SpanContext spanContext = Span.current().getSpanContext();
        if (!spanContext.isValid()) {
            MDC.remove("trace_id");
            MDC.remove("span_id");
            return;
        }
        MDC.put("trace_id", spanContext.getTraceId());
        MDC.put("span_id", spanContext.getSpanId());
    }

    private static void clearMdc() {
        MDC.remove("request_id");
        MDC.remove("trace_id");
        MDC.remove("span_id");
        MDC.remove("http.method");
        MDC.remove("http.route");
        MDC.remove("url.path");
        MDC.remove("http.status_code");
        MDC.remove("client.address");
        MDC.remove("service.name");
        MDC.remove("service.namespace");
        MDC.remove("service.version");
        MDC.remove("deployment.environment");
    }

    private static String resolveClientAddress(ContainerRequestContext requestContext) {
        return firstNonBlank(
                requestContext.getHeaderString("X-Forwarded-For"),
                requestContext.getHeaderString("X-Real-IP"),
                "unknown");
    }

    private String resolveRoute(ContainerRequestContext requestContext) {
        if (resourceInfo != null && resourceInfo.getResourceMethod() != null) {
            String classPath = resourceInfo.getResourceClass().isAnnotationPresent(jakarta.ws.rs.Path.class)
                    ? resourceInfo.getResourceClass().getAnnotation(jakarta.ws.rs.Path.class).value()
                    : "";
            String methodPath = resourceInfo.getResourceMethod().isAnnotationPresent(jakarta.ws.rs.Path.class)
                    ? resourceInfo.getResourceMethod().getAnnotation(jakarta.ws.rs.Path.class).value()
                    : "";
            String route = (classPath + "/" + methodPath).replaceAll("/+", "/");
            return route.startsWith("/") ? route : "/" + route;
        }

        return "/" + requestContext.getUriInfo().getPath();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

package br.com.oficina.autenticacao.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;

@Path("/.well-known")
@Produces(MediaType.APPLICATION_JSON)
public class OidcMetadataResource {

    @ConfigProperty(name = "oficina.auth.issuer", defaultValue = "oficina-api")
    String issuer;

    @Inject JwtKeySetService jwtKeySetService;

    @GET
    @Path("/openid-configuration")
    public Map<String, Object> openidConfiguration() {
        String normalizedIssuer = issuer();
        return Map.of(
                "issuer", normalizedIssuer,
                "jwks_uri", normalizedIssuer + "/.well-known/jwks.json",
                "token_endpoint", normalizedIssuer + "/auth/token",
                "response_types_supported", List.of("token"),
                "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256"),
                "scopes_supported", List.of("openid", "oficina-app"));
    }

    @GET
    @Path("/jwks.json")
    public Map<String, Object> jwks() {
        return jwtKeySetService.jwks();
    }

    private String issuer() {
        String value = issuer == null || issuer.isBlank() ? "oficina-api" : issuer;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

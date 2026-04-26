package br.com.oficina.autenticacao.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.InternalServerErrorException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class JwtKeySetService {

    @ConfigProperty(name = "mp.jwt.verify.publickey")
    Optional<String> publicKey;

    @ConfigProperty(name = "mp.jwt.verify.publickey.location")
    Optional<String> publicKeyLocation;

    @ConfigProperty(name = "oficina.auth.key-id", defaultValue = "oficina-lab-rsa")
    String keyId;

    public Map<String, Object> jwks() {
        return Map.of("keys", List.of(jwk()));
    }

    private Map<String, Object> jwk() {
        RSAPublicKey rsaPublicKey = publicKey();
        return Map.of(
                "kty", "RSA",
                "use", "sig",
                "kid", configured(keyId, "oficina-lab-rsa"),
                "alg", "RS256",
                "n", unsignedBase64Url(rsaPublicKey.getModulus()),
                "e", unsignedBase64Url(rsaPublicKey.getPublicExponent()));
    }

    private RSAPublicKey publicKey() {
        try {
            String pem = publicKey
                    .filter(value -> !value.isBlank())
                    .orElseGet(this::readPublicKeyLocation);
            String base64 = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(base64);
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
            return (RSAPublicKey) key;
        } catch (Exception exception) {
            throw new InternalServerErrorException("Nao foi possivel publicar a chave publica JWT", exception);
        }
    }

    private String readPublicKeyLocation() {
        String location = publicKeyLocation
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new InternalServerErrorException("Chave publica JWT nao configurada"));

        try {
            if (location.startsWith("file:")) {
                return Files.readString(Path.of(location.substring("file:".length())));
            }
            if (location.startsWith("classpath:")) {
                return readClasspath(location.substring("classpath:".length()));
            }
            return Files.readString(Path.of(location));
        } catch (IOException exception) {
            throw new InternalServerErrorException("Nao foi possivel ler a chave publica JWT", exception);
        }
    }

    private static String readClasspath(String resource) throws IOException {
        String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(normalized)) {
            if (input == null) {
                throw new IOException("Recurso nao encontrado: " + normalized);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String unsignedBase64Url(BigInteger value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray()[0] == 0
                ? java.util.Arrays.copyOfRange(value.toByteArray(), 1, value.toByteArray().length)
                : value.toByteArray());
    }

    private static String configured(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

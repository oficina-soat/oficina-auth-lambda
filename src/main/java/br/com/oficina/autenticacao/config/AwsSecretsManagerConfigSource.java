package br.com.oficina.autenticacao.config;

import org.eclipse.microprofile.config.spi.ConfigSource;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class AwsSecretsManagerConfigSource implements ConfigSource {
    private static final String DATASOURCE_USERNAME_ENV = "QUARKUS_DATASOURCE_USERNAME";
    private static final String DATASOURCE_PASSWORD_ENV = "QUARKUS_DATASOURCE_PASSWORD";
    private static final String JWT_VERIFY_PUBLIC_KEY_ENV = "MP_JWT_VERIFY_PUBLICKEY";
    private static final String JWT_VERIFY_PUBLIC_KEY_LOCATION_ENV = "MP_JWT_VERIFY_PUBLICKEY_LOCATION";
    private static final String JWT_SIGN_KEY_ENV = "SMALLRYE_JWT_SIGN_KEY";
    private static final String JWT_SIGN_KEY_LOCATION_ENV = "SMALLRYE_JWT_SIGN_KEY_LOCATION";
    private static final String JWT_PRIVATE_KEY_FIELD_ENV = "JWT_SECRET_PRIVATE_KEY_FIELD";
    private static final String JWT_PUBLIC_KEY_FIELD_ENV = "JWT_SECRET_PUBLIC_KEY_FIELD";
    private static final String DATASOURCE_USERNAME_SECRET_ENV = "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_USERNAME_";
    private static final String DATASOURCE_PASSWORD_SECRET_ENV = "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_PASSWORD_";
    private static final String JWT_SIGN_KEY_SECRET_ENV = "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__SMALLRYE_JWT_SIGN_KEY_";
    private static final String JWT_VERIFY_PUBLIC_KEY_SECRET_ENV = "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__MP_JWT_VERIFY_PUBLICKEY_";
    private static final String DATASOURCE_USERNAME_PROPERTY = "quarkus.datasource.username";
    private static final String DATASOURCE_PASSWORD_PROPERTY = "quarkus.datasource.password";
    private static final String JWT_VERIFY_PUBLIC_KEY_PROPERTY = "mp.jwt.verify.publickey";
    private static final String JWT_SIGN_KEY_PROPERTY = "smallrye.jwt.sign.key";
    private static final String DEFAULT_JWT_PRIVATE_KEY_FIELD = "privateKeyPem";
    private static final String DEFAULT_JWT_PUBLIC_KEY_FIELD = "publicKeyPem";
    private static final int ORDINAL = 275;

    private final Map<String, String> environment;
    private final SecretLoader providedSecretLoader;
    private final Map<String, String> secretCache = new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, String>> cachedProperties = new AtomicReference<>();
    private final AtomicReference<SecretLoader> runtimeSecretLoader = new AtomicReference<>();

    @SuppressWarnings("unused")
    public AwsSecretsManagerConfigSource() {
        this(System.getenv(), null);
    }

    AwsSecretsManagerConfigSource(Map<String, String> environment, SecretLoader secretLoader) {
        this.environment = Map.copyOf(environment);
        this.providedSecretLoader = secretLoader;
    }

    @Override
    public Map<String, String> getProperties() {
        return loadProperties();
    }

    @Override
    public Set<String> getPropertyNames() {
        return loadProperties().keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return loadProperties().get(propertyName);
    }

    @Override
    public String getName() {
        return "aws-secrets-manager-config-source";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    private Map<String, String> loadProperties() {
        Map<String, String> existing = cachedProperties.get();
        if (existing != null) {
            return existing;
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        resolveSecretProperty(resolved, DATASOURCE_USERNAME_ENV, DATASOURCE_USERNAME_SECRET_ENV, DATASOURCE_USERNAME_PROPERTY, false);
        resolveSecretProperty(resolved, DATASOURCE_PASSWORD_ENV, DATASOURCE_PASSWORD_SECRET_ENV, DATASOURCE_PASSWORD_PROPERTY, false);
        resolveSecretProperty(resolved, JWT_SIGN_KEY_ENV, JWT_SIGN_KEY_SECRET_ENV, JWT_SIGN_KEY_PROPERTY, true);
        resolveSecretProperty(resolved, JWT_VERIFY_PUBLIC_KEY_ENV, JWT_VERIFY_PUBLIC_KEY_SECRET_ENV, JWT_VERIFY_PUBLIC_KEY_PROPERTY, true);

        Map<String, String> immutable = Collections.unmodifiableMap(resolved);
        if (cachedProperties.compareAndSet(null, immutable)) {
            return immutable;
        }
        return cachedProperties.get();
    }

    private void resolveSecretProperty(Map<String, String> properties, String directEnvName, String secretEnvName,
                                       String propertyName, boolean skipWhenLocationConfigured) {
        if (hasText(environment.get(directEnvName))) {
            return;
        }
        if (skipWhenLocationConfigured && (hasText(environment.get(JWT_SIGN_KEY_LOCATION_ENV)) || hasText(environment.get(JWT_VERIFY_PUBLIC_KEY_LOCATION_ENV)))) {
            if (JWT_SIGN_KEY_PROPERTY.equals(propertyName) || JWT_VERIFY_PUBLIC_KEY_PROPERTY.equals(propertyName)) {
                return;
            }
        }

        String secretName = trimmed(environment.get(secretEnvName));
        if (secretName == null) {
            return;
        }

        String secretValue = trimmed(secretCache.computeIfAbsent(secretName, this::loadSecret));
        if (secretValue == null) {
            throw new IllegalStateException("Secret " + secretName + " nao contem SecretString legivel.");
        }
        secretValue = resolveJwtPemFromJson(propertyName, secretValue);
        properties.put(propertyName, secretValue);
    }

    private String loadSecret(String secretName) {
        if (providedSecretLoader != null) {
            return providedSecretLoader.load(secretName);
        }

        SecretLoader existing = runtimeSecretLoader.get();
        if (existing != null) {
            return existing.load(secretName);
        }

        SecretLoader created = new AwsSdkSecretLoader(environment);
        if (runtimeSecretLoader.compareAndSet(null, created)) {
            return created.load(secretName);
        }
        return runtimeSecretLoader.get().load(secretName);
    }

    private static boolean hasText(String value) {
        return trimmed(value) != null;
    }

    private String resolveJwtPemFromJson(String propertyName, String secretValue) {
        String fieldName = switch (propertyName) {
            case JWT_SIGN_KEY_PROPERTY -> configuredField(JWT_PRIVATE_KEY_FIELD_ENV, DEFAULT_JWT_PRIVATE_KEY_FIELD);
            case JWT_VERIFY_PUBLIC_KEY_PROPERTY -> configuredField(JWT_PUBLIC_KEY_FIELD_ENV, DEFAULT_JWT_PUBLIC_KEY_FIELD);
            default -> null;
        };
        if (fieldName == null || !looksLikeJsonObject(secretValue)) {
            return secretValue;
        }

        String extracted = extractJsonStringValue(secretValue, fieldName);
        return extracted == null ? secretValue : extracted;
    }

    private String configuredField(String envName, String fallback) {
        String value = trimmed(environment.get(envName));
        return value == null ? fallback : value;
    }

    private static boolean looksLikeJsonObject(String value) {
        String trimmed = trimmed(value);
        return trimmed != null && trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    private static String extractJsonStringValue(String json, String fieldName) {
        String quotedField = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(quotedField);
        if (keyIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', keyIndex + quotedField.length());
        if (colonIndex < 0) {
            return null;
        }

        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int i = valueStart + 1; i < json.length(); i++) {
            char current = json.charAt(i);
            if (escaping) {
                builder.append(unescapeJsonChar(current));
                escaping = false;
                continue;
            }
            if (current == '\\') {
                escaping = true;
                continue;
            }
            if (current == '"') {
                return builder.toString();
            }
            builder.append(current);
        }
        return null;
    }

    private static char unescapeJsonChar(char value) {
        return switch (value) {
            case '"', '\\', '/' -> value;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> value;
        };
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @FunctionalInterface
    interface SecretLoader {
        String load(String secretName);
    }

    private static final class AwsSdkSecretLoader implements SecretLoader {
        private final SecretsManagerClient client;

        private AwsSdkSecretLoader(Map<String, String> environment) {
            var builder = SecretsManagerClient.builder()
                    .credentialsProvider(DefaultCredentialsProvider.builder().build())
                    .httpClientBuilder(UrlConnectionHttpClient.builder());
            String region = trimmed(environment.get("AWS_REGION"));
            if (region != null) {
                builder.region(Region.of(region));
            }
            this.client = builder.build();
        }

        @Override
        public String load(String secretName) {
            return client.getSecretValue(GetSecretValueRequest.builder()
                            .secretId(secretName)
                            .build())
                    .secretString();
        }
    }
}

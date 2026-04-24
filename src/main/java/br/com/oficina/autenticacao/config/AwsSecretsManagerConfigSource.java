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
import java.util.Objects;
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
    private static final String DATASOURCE_USERNAME_SECRET_ENV = "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_USERNAME_";
    private static final String DATASOURCE_PASSWORD_SECRET_ENV = "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_PASSWORD_";
    private static final String JWT_SIGN_KEY_SECRET_ENV = "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__SMALLRYE_JWT_SIGN_KEY_";
    private static final String JWT_VERIFY_PUBLIC_KEY_SECRET_ENV = "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__MP_JWT_VERIFY_PUBLICKEY_";
    private static final String DATASOURCE_USERNAME_PROPERTY = "quarkus.datasource.username";
    private static final String DATASOURCE_PASSWORD_PROPERTY = "quarkus.datasource.password";
    private static final String JWT_VERIFY_PUBLIC_KEY_PROPERTY = "mp.jwt.verify.publickey";
    private static final String JWT_SIGN_KEY_PROPERTY = "smallrye.jwt.sign.key";
    private static final int ORDINAL = 275;

    private final Map<String, String> environment;
    private final SecretLoader secretLoader;
    private final Map<String, String> secretCache = new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, String>> cachedProperties = new AtomicReference<>();

    @SuppressWarnings("unused")
    public AwsSecretsManagerConfigSource() {
        this(System.getenv(), new AwsSdkSecretLoader(System.getenv()));
    }

    AwsSecretsManagerConfigSource(Map<String, String> environment, SecretLoader secretLoader) {
        this.environment = Map.copyOf(environment);
        this.secretLoader = Objects.requireNonNull(secretLoader, "secretLoader");
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

        String secretValue = trimmed(secretCache.computeIfAbsent(secretName, secretLoader::load));
        if (secretValue == null) {
            throw new IllegalStateException("Secret " + secretName + " nao contem SecretString legivel.");
        }
        properties.put(propertyName, secretValue);
    }

    private static boolean hasText(String value) {
        return trimmed(value) != null;
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

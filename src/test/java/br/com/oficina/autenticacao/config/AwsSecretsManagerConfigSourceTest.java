package br.com.oficina.autenticacao.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsSecretsManagerConfigSourceTest {

    @Test
    void shouldResolveDatasourceAndJwtFromSecretMappings() {
        AwsSecretsManagerConfigSource configSource = new AwsSecretsManagerConfigSource(
                Map.of(
                        "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_USERNAME_", "db/user",
                        "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_PASSWORD_", "db/password",
                        "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__SMALLRYE_JWT_SIGN_KEY_", "jwt/private",
                        "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__MP_JWT_VERIFY_PUBLICKEY_", "jwt/public"),
                secretName -> switch (secretName) {
                    case "db/user" -> "oficina_auth_lambda";
                    case "db/password" -> "super-secret";
                    case "jwt/private" -> "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----";
                    case "jwt/public" -> "-----BEGIN PUBLIC KEY-----\nxyz\n-----END PUBLIC KEY-----";
                    default -> throw new IllegalArgumentException(secretName);
                });

        assertEquals("oficina_auth_lambda", configSource.getValue("quarkus.datasource.username"));
        assertEquals("super-secret", configSource.getValue("quarkus.datasource.password"));
        assertEquals("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----", configSource.getValue("smallrye.jwt.sign.key"));
        assertEquals("-----BEGIN PUBLIC KEY-----\nxyz\n-----END PUBLIC KEY-----", configSource.getValue("mp.jwt.verify.publickey"));
    }

    @Test
    void shouldResolveJwtPemFieldsFromSharedJsonSecret() {
        AwsSecretsManagerConfigSource configSource = new AwsSecretsManagerConfigSource(
                Map.of(
                        "JWT_SECRET_PRIVATE_KEY_FIELD", "privateKeyPem",
                        "JWT_SECRET_PUBLIC_KEY_FIELD", "publicKeyPem",
                        "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__SMALLRYE_JWT_SIGN_KEY_", "jwt/shared",
                        "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__MP_JWT_VERIFY_PUBLICKEY_", "jwt/shared"),
                secretName -> {
                    assertEquals("jwt/shared", secretName);
                    return """
                            {
                              "privateKeyPem": "-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----",
                              "publicKeyPem": "-----BEGIN PUBLIC KEY-----\\nxyz\\n-----END PUBLIC KEY-----"
                            }
                            """;
                });

        assertEquals("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----", configSource.getValue("smallrye.jwt.sign.key"));
        assertEquals("-----BEGIN PUBLIC KEY-----\nxyz\n-----END PUBLIC KEY-----", configSource.getValue("mp.jwt.verify.publickey"));
    }

    @Test
    void shouldNotOverrideDirectEnvironmentValues() {
        AwsSecretsManagerConfigSource configSource = new AwsSecretsManagerConfigSource(
                Map.of(
                        "QUARKUS_DATASOURCE_USERNAME", "inline-user",
                        "QUARKUS_DATASOURCE_PASSWORD", "inline-password",
                        "SMALLRYE_JWT_SIGN_KEY", "inline-private",
                        "MP_JWT_VERIFY_PUBLICKEY", "inline-public",
                        "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_USERNAME_", "db/user",
                        "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_PASSWORD_", "db/password"),
                ignored -> {
                    throw new AssertionError("Secrets Manager nao deveria ser consultado");
                });

        assertTrue(configSource.getProperties().isEmpty());
    }
}

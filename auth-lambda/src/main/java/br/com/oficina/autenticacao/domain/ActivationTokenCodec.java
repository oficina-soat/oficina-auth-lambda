package br.com.oficina.autenticacao.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

final class ActivationTokenCodec {
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom;

    ActivationTokenCodec() {
        this(new SecureRandom());
    }

    ActivationTokenCodec(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    String generate() {
        var bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível para token de ativação.", exception);
        }
    }
}

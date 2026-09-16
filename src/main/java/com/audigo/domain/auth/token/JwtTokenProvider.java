package com.audigo.domain.auth.token;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final SecretKeySpec secretKey;

    public JwtTokenProvider(
            ObjectMapper objectMapper,
            @Value("${audigo.auth.jwt-secret}") String jwtSecret
    ) {
        this.objectMapper = objectMapper;
        this.secretKey = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String createAccessToken(Long userId, long ttlSeconds) {
        Instant now = Instant.now();
        Map<String, Object> header = Map.of(
                "alg", "HS256",
                "typ", "JWT"
        );
        Map<String, Object> payload = Map.of(
                "sub", String.valueOf(userId),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(ttlSeconds).getEpochSecond(),
                "token_type", "access"
        );

        String encodedHeader = encodeJson(header);
        String encodedPayload = encodeJson(payload);
        String unsignedToken = encodedHeader + "." + encodedPayload;
        return unsignedToken + "." + sign(unsignedToken);
    }

    public Optional<Long> parseAccessToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        String unsignedToken = parts[0] + "." + parts[1];
        if (!sign(unsignedToken).equals(parts[2])) {
            return Optional.empty();
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(BASE64_URL_DECODER.decode(parts[1]), MAP_TYPE);
            if (!"access".equals(payload.get("token_type"))) {
                return Optional.empty();
            }

            Object exp = payload.get("exp");
            if (!(exp instanceof Number expiration) || Instant.now().getEpochSecond() >= expiration.longValue()) {
                return Optional.empty();
            }

            Object subject = payload.get("sub");
            if (!(subject instanceof String userId)) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(userId));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize JWT payload.", exception);
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKey);
            return BASE64_URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign JWT.", exception);
        }
    }
}

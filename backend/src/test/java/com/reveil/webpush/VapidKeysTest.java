package com.reveil.webpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VapidKeysTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    @Test
    void enTeteAuthorizationPorteUnJwtEs256VerifiableParLaClePublique() throws Exception {
        VapidKeys keys = VapidKeys.generate();
        String header = keys.authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc:def"),
                "mailto:admin@example.com", NOW);

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + keys.publicKey());
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        ObjectMapper json = new ObjectMapper();
        JsonNode head = json.readTree(EcKeys.base64Url(parts[0]));
        JsonNode claims = json.readTree(EcKeys.base64Url(parts[1]));
        assertThat(head.get("alg").asText()).isEqualTo("ES256");
        assertThat(claims.get("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("sub").asText()).isEqualTo("mailto:admin@example.com");
        assertThat(claims.get("exp").asLong()).isEqualTo(NOW.plus(VapidKeys.JWT_VALIDITY).getEpochSecond());

        byte[] signature = EcKeys.base64Url(parts[2]);
        assertThat(signature).hasSize(64);
        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(keys.ecPublicKey());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(signature)).isTrue();
    }

    @Test
    void relitDesClesBase64UrlEtRefuseUnePaireIncoherente() {
        VapidKeys a = VapidKeys.generate();
        VapidKeys b = VapidKeys.generate();
        VapidKeys reread = VapidKeys.fromBase64Url(a.publicKey(), a.privateKeyBase64Url());
        assertThat(reread.publicKey()).isEqualTo(a.publicKey());
        assertThat(EcKeys.base64Url(a.publicKey())).hasSize(65);
        assertThatThrownBy(() -> VapidKeys.fromBase64Url(a.publicKey(), b.privateKeyBase64Url()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

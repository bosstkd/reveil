package com.reveil.webpush;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;

/**
 * Identité VAPID du serveur (RFC 8292) : le navigateur s'abonne avec la clé publique ; chaque envoi porte un
 * JWT ES256 signé par la clé privée, que le service push vérifie avant d'accepter le message.
 */
public final class VapidKeys {

    /** Durée de validité du JWT (le maximum toléré par les services push est 24 h). */
    static final Duration JWT_VALIDITY = Duration.ofHours(12);

    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String publicKeyBase64Url;

    private VapidKeys(ECPublicKey publicKey, ECPrivateKey privateKey) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.publicKeyBase64Url = EcKeys.base64Url(EcKeys.encode(publicKey));
    }

    static VapidKeys generate() {
        KeyPair pair = EcKeys.generate();
        return new VapidKeys((ECPublicKey) pair.getPublic(), (ECPrivateKey) pair.getPrivate());
    }

    /** Clés au format usuel des outils Web Push : point public de 65 octets et scalaire privé de 32 octets, base64url. */
    static VapidKeys fromBase64Url(String publicKey, String privateKey) {
        VapidKeys keys = new VapidKeys(EcKeys.publicKey(EcKeys.base64Url(publicKey)),
                EcKeys.privateKey(EcKeys.base64Url(privateKey)));
        keys.checkPair();
        return keys;
    }

    /** Clé publique (base64url) transmise au navigateur ({@code applicationServerKey}). */
    public String publicKey() {
        return publicKeyBase64Url;
    }

    String privateKeyBase64Url() {
        return EcKeys.base64Url(EcKeys.encode(privateKey));
    }

    ECPublicKey ecPublicKey() {
        return publicKey;
    }

    /** En-tête {@code Authorization} d'un envoi vers {@code endpoint} : {@code vapid t=<JWT>, k=<clé publique>}. */
    String authorization(URI endpoint, String subject, Instant now) {
        String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String header = EcKeys.base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = EcKeys.base64Url(("{\"aud\":\"" + json(audience) + "\",\"exp\":"
                + now.plus(JWT_VALIDITY).getEpochSecond() + ",\"sub\":\"" + json(subject) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        return "vapid t=" + signingInput + "." + EcKeys.base64Url(sign(signingInput)) + ", k=" + publicKeyBase64Url;
    }

    private byte[] sign(String input) {
        try {
            // Format P1363 (r || s, 64 octets) exigé par JWS, et non DER.
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signature VAPID impossible", e);
        }
    }

    private void checkPair() {
        try {
            byte[] probe = "reveil".getBytes(StandardCharsets.US_ASCII);
            byte[] sig = sign("reveil");
            Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
            verify.initVerify(publicKey);
            verify.update(probe);
            if (!verify.verify(sig)) {
                throw new IllegalArgumentException("Les clés VAPID publique et privée ne correspondent pas");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Clés VAPID invalides", e);
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

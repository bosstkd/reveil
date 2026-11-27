package com.reveil.webpush;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement du contenu d'un message Web Push (RFC 8291, codage {@code aes128gcm} de la RFC 8188) :
 * seul le navigateur abonné (clé {@code p256dh} + secret {@code auth}) peut le lire ; le service push
 * (FCM, Mozilla, Apple…) ne voit qu'un contenu opaque.
 */
final class WebPushEncryption {

    /** Taille d'enregistrement annoncée : un seul enregistrement (contenu ≤ 4 Ko). */
    static final int RECORD_SIZE = 4096;
    /** Contenu maximal en clair : 4096 − en-tête de 86 octets − étiquette GCM (16) − délimiteur (1). */
    static final int MAX_PLAINTEXT = 3993;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushEncryption() {
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, EcKeys.generate(), salt);
    }

    /** Variante déterministe (clé éphémère et sel fournis) : vecteurs de test de la RFC 8291. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair ephemeral, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("Contenu Web Push trop long (" + plaintext.length + " octets)");
        }
        try {
            ECPublicKey uaKey = EcKeys.publicKey(uaPublic);
            byte[] asPublic = EcKeys.encode((ECPublicKey) ephemeral.getPublic());

            KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
            ecdh.init(ephemeral.getPrivate());
            ecdh.doPhase(uaKey, true);
            byte[] sharedSecret = ecdh.generateSecret();

            // IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" || 0x00 || ua_public || as_public, 32)
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
            byte[] ikm = expand(hmac(authSecret, sharedSecret), keyInfo, 32);
            byte[] prk = hmac(salt, ikm);
            byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
            byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = aes.doFinal(concat(plaintext, new byte[] {0x02})); // 0x02 : dernier enregistrement

            return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ciphertext.length)
                    .put(salt)
                    .putInt(RECORD_SIZE)
                    .put((byte) asPublic.length)
                    .put(asPublic)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement Web Push impossible", e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** HKDF-Expand (RFC 5869) limité à un bloc (≤ 32 octets). */
    static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        return Arrays.copyOf(hmac(prk, concat(info, new byte[] {0x01})), length);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) {
            length += p.length;
        }
        ByteBuffer out = ByteBuffer.allocate(length);
        for (byte[] p : parts) {
            out.put(p);
        }
        return out.array();
    }
}

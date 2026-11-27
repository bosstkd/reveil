package com.reveil.webpush;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Navigateur simulé : clés d'abonnement et déchiffrement d'un message Web Push (côté récepteur de la RFC 8291). */
final class TestBrowser {

    final ECPublicKey publicKey;
    final ECPrivateKey privateKey;
    final byte[] auth;

    TestBrowser() {
        KeyPair pair = EcKeys.generate();
        this.publicKey = (ECPublicKey) pair.getPublic();
        this.privateKey = (ECPrivateKey) pair.getPrivate();
        this.auth = new byte[16];
        new SecureRandom().nextBytes(auth);
    }

    TestBrowser(String publicKey, String privateKey, String auth) {
        this.publicKey = EcKeys.publicKey(EcKeys.base64Url(publicKey));
        this.privateKey = EcKeys.privateKey(EcKeys.base64Url(privateKey));
        this.auth = EcKeys.base64Url(auth);
    }

    String p256dh() {
        return EcKeys.base64Url(EcKeys.encode(publicKey));
    }

    String authSecret() {
        return EcKeys.base64Url(auth);
    }

    WebPushSubscription subscription(String endpoint) {
        return new WebPushSubscription(endpoint, p256dh(), authSecret(), "u1", "s1", java.time.Instant.now());
    }

    String decrypt(byte[] body) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        int rs = in.getInt();
        byte[] asPublic = new byte[in.get()];
        in.get(asPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        if (rs != WebPushEncryption.RECORD_SIZE) {
            throw new IllegalStateException("rs inattendu : " + rs);
        }
        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(privateKey);
        ecdh.doPhase(EcKeys.publicKey(asPublic), true);
        byte[] keyInfo = WebPushEncryption.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
                EcKeys.encode(publicKey), asPublic);
        byte[] ikm = WebPushEncryption.expand(WebPushEncryption.hmac(auth, ecdh.generateSecret()), keyInfo, 32);
        byte[] prk = WebPushEncryption.hmac(salt, ikm);
        byte[] cek = WebPushEncryption.expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushEncryption.expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = aes.doFinal(ciphertext);
        if (plain[plain.length - 1] != 0x02) {
            throw new IllegalStateException("Délimiteur de dernier enregistrement absent");
        }
        return new String(Arrays.copyOf(plain, plain.length - 1), StandardCharsets.UTF_8);
    }
}

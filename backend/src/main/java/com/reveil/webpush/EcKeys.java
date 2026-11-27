package com.reveil.webpush;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.ECFieldFp;
import java.util.Arrays;
import java.util.Base64;

/** Clés ECDSA/ECDH P-256 au format brut du Web Push (point non compressé de 65 octets, scalaire de 32 octets). */
final class EcKeys {

    static final ECParameterSpec P256 = p256();

    private EcKeys() {
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Courbe P-256 indisponible", e);
        }
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(P256);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Génération de clé P-256 impossible", e);
        }
    }

    /** Point non compressé {@code 0x04 || X || Y}. */
    static byte[] encode(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(unsigned(key.getW().getAffineX(), 32), 0, out, 1, 32);
        System.arraycopy(unsigned(key.getW().getAffineY(), 32), 0, out, 33, 32);
        return out;
    }

    static byte[] encode(ECPrivateKey key) {
        return unsigned(key.getS(), 32);
    }

    /** Décode un point non compressé et vérifie qu'il appartient à la courbe (sinon {@link IllegalArgumentException}). */
    static ECPublicKey publicKey(byte[] raw) {
        if (raw == null || raw.length != 65 || raw[0] != 0x04) {
            throw new IllegalArgumentException("Clé publique P-256 attendue (65 octets, non compressée)");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        BigInteger p = ((ECFieldFp) P256.getCurve().getField()).getP();
        BigInteger a = P256.getCurve().getA();
        BigInteger b = P256.getCurve().getB();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0
                || !y.modPow(BigInteger.TWO, p).equals(x.pow(3).add(a.multiply(x)).add(b).mod(p))) {
            throw new IllegalArgumentException("Point hors de la courbe P-256");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Clé publique P-256 invalide", e);
        }
    }

    static ECPrivateKey privateKey(byte[] raw) {
        if (raw == null || raw.length != 32) {
            throw new IllegalArgumentException("Clé privée P-256 attendue (32 octets)");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Clé privée P-256 invalide", e);
        }
    }

    static byte[] base64Url(String value) {
        return Base64.getUrlDecoder().decode(value.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] unsigned(BigInteger n, int length) {
        byte[] bytes = n.toByteArray();
        if (bytes.length == length) {
            return bytes;
        }
        byte[] out = new byte[length];
        int copy = Math.min(bytes.length, length);
        System.arraycopy(bytes, bytes.length - copy, out, length - copy, copy);
        return out;
    }
}

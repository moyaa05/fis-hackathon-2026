package com.fis.hackathon.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Firmas Ed25519 con el JDK 21, sin librerías externas.
 * Claves en base64: pública = X.509 SPKI DER; privada = PKCS#8 DER (SPEC §3.2).
 * Lo que se firma es SIEMPRE el texto del challenge en UTF-8.
 */
public final class Ed25519 {

    private Ed25519() {}

    public static PublicKey publicKey(String spkiB64) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(spkiB64)));
    }

    public static PrivateKey privateKey(String pkcs8B64) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8B64)));
    }

    /** Firma el mensaje y devuelve la firma (64 bytes) en base64 estándar. */
    public static String sign(PrivateKey key, String message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** true solo si la firma es válida para ese mensaje y esa clave pública. Nunca lanza excepción. */
    public static boolean verify(String spkiB64, String message, byte[] signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey(spkiB64));
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return s.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}

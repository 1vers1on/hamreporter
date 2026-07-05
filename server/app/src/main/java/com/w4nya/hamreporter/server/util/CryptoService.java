package com.w4nya.hamreporter.server.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class CryptoService {
    public static final String ALGORITHM = "Ed25519";

    private CryptoService() {}

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available; use JDK 15+", e);
        }
    }

    public static String signBase64(PrivateKey sk, String data) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(sk);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException("sign failed", e);
        }
    }

    public static boolean verifyBase64(PublicKey pk, String data, String signatureBase64) {
        if (pk == null || signatureBase64 == null) return false;
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(pk);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    public static String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[h.length * 2];
            String d = "0123456789abcdef";
            for (int i = 0; i < h.length; i++) {
                hex[i*2] = d.charAt((h[i] >> 4) & 0xF);
                hex[i*2+1] = d.charAt(h[i] & 0xF);
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String publicKeyBase64(PublicKey pk) {
        return Base64.getEncoder().encodeToString(pk.getEncoded());
    }

    public static String privateKeyBase64(PrivateKey sk) {
        return Base64.getEncoder().encodeToString(sk.getEncoded());
    }

    public static PublicKey publicKeyFromBase64(String b64) {
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(b64));
            return KeyFactory.getInstance(ALGORITHM).generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid public key", e);
        }
    }

    public static PrivateKey privateKeyFromBase64(String b64) {
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64));
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid private key", e);
        }
    }

    public static void saveKeyPair(KeyPair kp, String privPath, String pubPath) throws Exception {
        Path privP = Paths.get(privPath);
        Path pubP = Paths.get(pubPath);
        Path privTmp = Paths.get(privPath + ".tmp");
        Path pubTmp = Paths.get(pubPath + ".tmp");

        Files.write(privTmp, Base64.getEncoder().encode(kp.getPrivate().getEncoded()));
        Files.write(pubTmp, Base64.getEncoder().encode(kp.getPublic().getEncoded()));

        Files.move(privTmp, privP, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.move(pubTmp, pubP, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        privP.toFile().setReadable(true, true);
        pubP.toFile().setReadable(true, true);
    }

    public static KeyPair loadKeyPair(String privPath, String pubPath) throws Exception {
        PrivateKey sk = privateKeyFromBase64(
                Files.readString(Paths.get(privPath)).trim());
        PublicKey pk = publicKeyFromBase64(
                Files.readString(Paths.get(pubPath)).trim());
        return new KeyPair(pk, sk);
    }

    public static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

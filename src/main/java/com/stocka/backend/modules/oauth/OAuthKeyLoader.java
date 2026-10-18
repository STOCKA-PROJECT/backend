package com.stocka.backend.modules.oauth;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * Builds the signing {@link JWKSet} of the authorization server from the configured PEM keys.
 *
 * <p>Keys are RSA PKCS#8 private keys ({@code openssl genpkey -algorithm RSA -pkeyopt
 * rsa_keygen_bits:2048}); the public half is derived from the CRT parameters so a single secret is
 * enough. A previous key can be kept in the set during a rotation so tokens signed with it stay
 * valid until they expire.
 */
public final class OAuthKeyLoader {

    private static final Logger log = LoggerFactory.getLogger(OAuthKeyLoader.class);
    static final int MIN_RSA_BITS = 2048;

    private OAuthKeyLoader() {
    }

    /**
     * Loads the key set.
     *
     * @param jwk        key settings
     * @param production whether the {@code prod} profile is active (a key is then mandatory)
     * @return the set with the current key first
     * @throws IllegalStateException when the key is missing in production or cannot be parsed
     */
    public static JWKSet load(OAuthServerProperties.Jwk jwk, boolean production) {
        List<JWK> keys = new ArrayList<>();
        if (jwk.hasPrivateKey()) {
            keys.add(parse(jwk.getPrivateKey(), jwk.getKid()));
        } else if (production) {
            throw new IllegalStateException("stocka.oauth.jwk.private-key is required in production. "
                    + "Set OAUTH_JWK_PRIVATE_KEY to a PKCS#8 RSA private key (PEM).");
        } else {
            log.warn("oauth_ephemeral_key: no OAUTH_JWK_PRIVATE_KEY configured; generating a throwaway "
                    + "RSA key. Access tokens will not survive a restart.");
            keys.add(generateEphemeral(jwk.getKid()));
        }
        if (jwk.hasPreviousPrivateKey()) {
            keys.add(parse(jwk.getPreviousPrivateKey(), jwk.getPreviousKid()));
        }
        return new JWKSet(keys);
    }

    /**
     * Parses a PKCS#8 RSA private key in PEM (or raw base64) form.
     *
     * @param pem the key text; literal {@code \n} sequences are accepted
     * @param kid key id to publish
     * @return the RSA JWK with public and private parts
     * @throws IllegalStateException on malformed or too-short keys
     */
    static RSAKey parse(String pem, String kid) {
        String body = pem.replace("\\n", "\n")
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("stocka.oauth.jwk.private-key is not valid base64/PEM", e);
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("stocka.oauth.jwk.private-key must be an RSA CRT key");
            }
            if (crt.getModulus().bitLength() < MIN_RSA_BITS) {
                throw new IllegalStateException("stocka.oauth.jwk.private-key must be at least "
                        + MIN_RSA_BITS + " bits");
            }
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return build(publicKey, privateKey, kid);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | ClassCastException e) {
            throw new IllegalStateException("stocka.oauth.jwk.private-key is not a PKCS#8 RSA key", e);
        }
    }

    /** Generates a 2048-bit RSA key for development and tests. */
    static RSAKey generateEphemeral(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_RSA_BITS);
            KeyPair pair = generator.generateKeyPair();
            return build((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate(), kid);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available", e);
        }
    }

    private static RSAKey build(RSAPublicKey publicKey, RSAPrivateKey privateKey, String kid) {
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }
}

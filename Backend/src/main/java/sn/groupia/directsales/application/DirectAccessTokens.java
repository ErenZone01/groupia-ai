package sn.groupia.directsales.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import sn.groupia.shared.api.ApiException;

/**
 * A customer-owned 256-bit random capability; only its domain-separated SHA-256 digest is stored.
 * Never log the original token, expose it in a query parameter, or treat a number as an authority.
 */
public final class DirectAccessTokens {
    private DirectAccessTokens() {}

    public static String hashForCreate(String token) {
        if (!wellFormed(token)) {
            throw new ApiException(
                    400, "INVALID_DIRECT_ACCESS_TOKEN",
                    "Jeton de suivi de commande invalide.");
        }
        return digest(token);
    }

    public static String hashForLookup(String token) {
        if (!wellFormed(token)) {
            throw new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
        }
        return digest(token);
    }

    private static boolean wellFormed(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            return bytes.length == 32
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String digest(String token) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(("groupia-direct-access-v1:" + token)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

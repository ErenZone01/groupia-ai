package sn.groupia.settlement.infrastructure;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.groupia.settlement.domain.model.PayoutAdministrativeApproval;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.ObjectMapper;

/**
 * Offline review boundary: operator-controlled manifest + original evidence, both hash checked.
 * Hashes prove integrity, not the truth of an attestation: independent review is mandatory. No URL
 * is fetched and no path comes from a request.
 */
@Component
public class ReviewedPayoutEvidence {
    private final ObjectMapper json;

    @Value("${groupia.settlement.payout-administration.approval-file:}")
    private String approvalFile;

    @Value("${groupia.settlement.payout-administration.approval-sha256:}")
    private String approvalSha256;

    public ReviewedPayoutEvidence(ObjectMapper json) {
        this.json = json;
    }

    public record Verified(PayoutAdministrativeApproval approval, String digest) {}

    public Verified read(String reference) {
        try {
            if (approvalFile == null
                    || approvalFile.isBlank()
                    || approvalSha256 == null
                    || !approvalSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException();
            var path = Path.of(approvalFile);
            byte[] manifest = bounded(path, 16384);
            if (!sha256(manifest).equals(approvalSha256)) throw new IllegalArgumentException();
            var approval = json.readValue(manifest, PayoutAdministrativeApproval.class);
            if (!reference.equals(approval.reference())
                    || !sha256(bounded(Path.of(approvalFile + ".evidence"), 2097152))
                            .equals(approval.evidenceSha256()))
                throw new IllegalArgumentException();
            return new Verified(approval, approvalSha256);
        } catch (Exception ex) {
            // Never expose file names, evidence contents, parser errors or keys.
            throw new ApiException(
                    409,
                    "PAYOUT_REVIEWED_EVIDENCE_REQUIRED",
                    "Preuve revue et autorisation serveur vérifiables requises.");
        }
    }

    private static byte[] bounded(Path path, int limit) throws java.io.IOException {
        try (var stream = Files.newInputStream(path)) {
            var bytes = stream.readNBytes(limit + 1);
            if (bytes.length == 0 || bytes.length > limit) throw new java.io.IOException();
            return bytes;
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}

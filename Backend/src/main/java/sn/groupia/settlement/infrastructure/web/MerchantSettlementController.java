package sn.groupia.settlement.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.settlement.api.dto.*;
import sn.groupia.settlement.application.service.MerchantSettlementService;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
class MerchantSettlementController {
    private final MerchantSettlementService settlements;

    MerchantSettlementController(MerchantSettlementService s) {
        settlements = s;
    }

    public record DestinationChange(
            UUID previousDestinationId,
            @NotBlank String method,
            @NotBlank String phoneE164,
            @NotBlank @Size(max = 160) String holderName,
            boolean confirmed) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unsupported destination field");
        }
    }

    public record PayoutRequest(@NotNull UUID destinationId, UUID feeQuoteId, boolean confirmed) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unsupported payout field");
        }
    }

    public record PayoutRecoveryRequest(boolean confirmed) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unsupported recovery field");
        }
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }

    @GetMapping("/merchant-balance")
    @Operation(operationId = "merchantBalance", summary = "Solde boutique et disponibilité")
    ResponseEntity<MerchantBalance> balance(@PathVariable UUID organizationId) {
        return response(settlements.balance(organizationId, null));
    }

    @GetMapping("/grouping-campaigns/{campaignId}/merchant-settlement")
    @Operation(operationId = "campaignMerchantSettlement", summary = "Fonds boutique d'un groupage")
    ResponseEntity<MerchantBalance> campaign(
            @PathVariable UUID organizationId, @PathVariable UUID campaignId) {
        return response(settlements.balance(organizationId, campaignId));
    }

    @GetMapping("/payout-destination")
    @Operation(
            operationId = "merchantPayoutDestination",
            summary = "Destination masquée des versements")
    ResponseEntity<PayoutDestinationView> destination(@PathVariable UUID organizationId) {
        return response(settlements.destination(organizationId));
    }

    @PutMapping("/payout-destination")
    @Operation(
            operationId = "changeMerchantPayoutDestination",
            summary = "Nouvelle révision de destination, propriétaire uniquement")
    ResponseEntity<PayoutDestinationView> destination(
            @PathVariable UUID organizationId, @Valid @RequestBody DestinationChange c) {
        return response(
                settlements.destination(
                        organizationId,
                        c.previousDestinationId(),
                        c.method(),
                        c.phoneE164(),
                        c.holderName(),
                        c.confirmed()));
    }

    @GetMapping("/grouping-campaigns/{campaignId}/merchant-payout-quote")
    @Operation(
            operationId = "quoteMerchantPayout",
            summary = "Devis frais retrait, réseau et montant net, sans transfert")
    ResponseEntity<PayoutFeeQuoteView> quoteCampaign(
            @PathVariable UUID organizationId,
            @PathVariable UUID campaignId,
            @RequestParam UUID destinationId,
            @RequestParam(required = false) java.math.BigDecimal amount) {
        return response(
                settlements.quoteCampaign(organizationId, campaignId, destinationId, amount));
    }

    @GetMapping("/direct-orders/{directOrderId}/merchant-payout-quote")
    @Operation(
            operationId = "quoteDirectMerchantPayout",
            summary = "Devis net du retrait d'une vente directe, sans transfert")
    ResponseEntity<PayoutFeeQuoteView> quoteDirect(
            @PathVariable UUID organizationId,
            @PathVariable UUID directOrderId,
            @RequestParam UUID destinationId,
            @RequestParam(required = false) java.math.BigDecimal amount) {
        return response(
                settlements.quoteDirect(organizationId, directOrderId, destinationId, amount));
    }

    @PostMapping("/grouping-campaigns/{campaignId}/merchant-payouts")
    @Operation(
            operationId = "requestMerchantPayout",
            summary = "Demander le montant du devis accepté pour un groupage")
    ResponseEntity<MerchantPayoutView> request(
            @PathVariable UUID organizationId,
            @PathVariable UUID campaignId,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PayoutRequest c) {
        return response(
                settlements.request(
                        organizationId,
                        campaignId,
                        key,
                        c.destinationId(),
                        c.confirmed(),
                        c.feeQuoteId()));
    }

    @PostMapping("/direct-orders/{directOrderId}/merchant-payouts")
    @Operation(
            operationId = "requestDirectMerchantPayout",
            summary =
                    "Demander le montant du devis accepté d'une vente directe, avec vérification des fonds")
    ResponseEntity<MerchantPayoutView> requestDirect(
            @PathVariable UUID organizationId,
            @PathVariable UUID directOrderId,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody PayoutRequest c) {
        return response(
                settlements.requestDirect(
                        organizationId,
                        directOrderId,
                        key,
                        c.destinationId(),
                        c.confirmed(),
                        c.feeQuoteId()));
    }

    @GetMapping("/merchant-payouts")
    @Operation(
            operationId = "merchantPayoutHistory",
            summary = "Historique des versements boutique")
    ResponseEntity<List<MerchantPayoutView>> history(@PathVariable UUID organizationId) {
        return response(settlements.payouts(organizationId));
    }

    @PostMapping("/merchant-payouts/{payoutId}/recover-pilot")
    @Operation(
            operationId = "recoverPilotMerchantPayout",
            summary =
                    "Opération exceptionnelle : un seul rejeu de la clé SasPay du retrait existant")
    ResponseEntity<MerchantPayoutView> recoverPilot(
            @PathVariable UUID organizationId,
            @PathVariable UUID payoutId,
            @Valid @RequestBody PayoutRecoveryRequest request) {
        return response(settlements.recoverPilot(organizationId, payoutId, request.confirmed()));
    }

    @PostMapping("/merchant-payouts/{payoutId}/reconcile")
    @Operation(
            operationId = "reconcileMerchantPayout",
            summary = "Vérification serveur, sans nouvel envoi")
    ResponseEntity<MerchantPayoutView> reconcile(
            @PathVariable UUID organizationId, @PathVariable UUID payoutId) {
        return response(settlements.reconcile(organizationId, payoutId));
    }
}

package sn.groupia.payment.infrastructure.web;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import sn.groupia.payment.api.dto.RefundView;
import sn.groupia.payment.api.dto.RefundReviewView;
import sn.groupia.payment.application.service.RefundService;
@RestController @RequestMapping("/api/organizations/{organizationId}/refunds")
class RefundController {
    private final RefundService refunds;
    RefundController(RefundService r){refunds=r;}
    @GetMapping("/review") @Operation(operationId="refundReviewQueue",summary="Régularisations à vérifier")
    ResponseEntity<List<RefundReviewView>> queue(@PathVariable UUID organizationId){return ResponseEntity.ok().header("Cache-Control","no-store").body(refunds.queue(organizationId));}
    @PostMapping("/{refundId}/reconcile") @Operation(operationId="reconcileRefund",summary="Vérifier ponctuellement un remboursement")
    ResponseEntity<RefundView> reconcile(@PathVariable UUID organizationId,@PathVariable UUID refundId){
        refunds.reconcile(organizationId,refundId);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(refunds.get(organizationId,refundId));
    }
}


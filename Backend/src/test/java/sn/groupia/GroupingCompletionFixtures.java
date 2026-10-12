package sn.groupia;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import sn.groupia.MerchantSettlementFixtures.Fixture;

class GroupingCompletionFixtures {
    static UUID shippingCredit(JdbcTemplate j, Fixture f) {
        return shippingCredit(j, f, 4500);
    }

    static UUID shippingCredit(JdbcTemplate j, Fixture f, int amount) {
        UUID assessment = UUID.randomUUID(), calculation = UUID.randomUUID();
        j.update(
                """
            insert into shipping_assessments(id,organization_id,campaign_id,order_id,snapshot,status,version,created_at,updated_at)
            select ?,o.organization_id,o.campaign_id,o.id,
                jsonb_build_object('organizationId',o.organization_id,'campaignId',o.campaign_id,'orderId',o.id,
                    'terms',o.shipping_terms_snapshot,'totalItems',1,'estimatedWeightKg',null,
                    'estimatedVolumeCbm',null,'estimatedShippingAmount',o.estimated_shipping_amount),
                'PENDING',0,now(),now()
            from orders o where o.id=?
            """,
                assessment,
                f.order());
        j.update(
                """
            insert into shipping_calculations(id,assessment_id,revision,kind,explanation,actor_id,recorded_at)
            values (?, ?,1,'FINAL',
                '{"origin":"PROVIDER_QUOTED","transportMode":"AIR","pricingMethod":"FIXED","method":"FIXED",
                 "currency":"XOF","amount":%d,"roundingRule":"HALF_UP","source":"MANAGER"}'::jsonb,?,now())
            """
                        .formatted(amount),
                calculation,
                assessment,
                f.actor());
        j.update(
                """
            update shipping_assessments set status='FINALIZED',version=version+1,
                current_final_calculation_id=?,updated_at=now() where id=?
            """,
                calculation,
                assessment);
        j.update(
                """
            update orders set fulfillment_status='PICKED_UP',ready_for_pickup_at=now(),
                picked_up_at=now(),version=version+1 where id=? and status='CONFIRMED'
            """,
                f.order());
        return calculation;
    }
}

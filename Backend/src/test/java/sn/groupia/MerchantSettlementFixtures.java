package sn.groupia;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
/** Synthetic facts for isolated PostgreSQL tests only, never application data repair. */
class MerchantSettlementFixtures {
    private static final String PASSWORD = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Synthetic merchant fixture 2026!");
    record Fixture(UUID org,UUID campaign,UUID order,UUID actor) {}
    static Fixture seed(JdbcTemplate j) {
        return seed(j,null);
    }
    static Fixture seed(JdbcTemplate j,Fixture existingBoutique) {
        var f=new Fixture(existingBoutique==null?UUID.randomUUID():existingBoutique.org(),UUID.randomUUID(),UUID.randomUUID(),existingBoutique==null?UUID.randomUUID():existingBoutique.actor());
        UUID customer=UUID.randomUUID();
        if(existingBoutique==null) {
        j.update("insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?,?,?,'ACTIVE',now(),now())",f.actor(),"+221"+String.format("%09d",Math.abs(f.actor().getLeastSignificantBits()%1000000000)),PASSWORD);
        j.update("insert into organizations(id,name,status,created_at,updated_at) values (?,'Boutique fixture','ACTIVE',now(),now())",f.org());
        j.update("insert into organization_members(id,organization_id,user_account_id,role,status,joined_at) values (?,?,?,'OWNER','ACTIVE',now())",UUID.randomUUID(),f.org(),f.actor());
        }
        j.update("""
            insert into grouping_campaigns(id,organization_id,public_identifier,slug,title,status,currency,transport_mode,
                shipping_pricing_method,shipping_collection_mode,shipping_rate,shipping_currency,rounding_rule,opened_at,created_at,updated_at)
            values (?,?,?,'fixture','Groupage fixture','OPEN','XOF','AIR','FIXED','ESTIMATED_AT_CHECKOUT',5000,'XOF','HALF_UP',now(),now(),now())
            """,f.campaign(),f.org(),UUID.randomUUID());
        j.update("insert into customers(id,organization_id,first_name,last_name,phone_e164,created_at,updated_at) values (?,?,'Cliente','Fixture','+221771234567',now(),now())",customer,f.org());
        String number="GRP-"+String.format("%019d",Math.abs(f.order().getMostSignificantBits()%1000000000000000000L));
        j.update("""
            insert into orders(id,organization_id,campaign_id,customer_id,order_number,status,fulfillment_status,currency,
                customer_contact_snapshot,goods_subtotal,platform_fee_amount,platform_fee_rule_snapshot,shipping_terms_snapshot,
                initial_shipping_charge_amount,estimated_shipping_amount,initial_amount_due,checkout_idempotency_key,checkout_request_fingerprint,
                tracking_token_hash,payment_deadline,tracking_token_expires_at,placed_at)
            values (?,?,?,?,?,'PENDING_PAYMENT','NOT_READY','XOF',
                '{"firstName":"Cliente","lastName":"Fixture","phoneE164":"+221771234567"}',20000,300,
                '{"type":"FIXED","currency":"XOF","amount":300,"version":1}',
                '{"transportMode":"AIR","shippingCollectionMode":"ESTIMATED_AT_CHECKOUT","shippingPricingMethod":"FIXED","shippingRate":5000,"shippingCurrency":"XOF","roundingRule":"HALF_UP"}',
                5000,5000,25300,?,repeat('a',64),?,now()+interval '30 minutes',now()+interval '180 days',now()-interval '1 minute')
            """,f.order(),f.org(),f.campaign(),customer,number,UUID.randomUUID().toString(),f.order().toString().replace("-","")+f.actor().toString().replace("-",""));
        return f;
    }
    static UUID settled(JdbcTemplate j,Fixture f,String purpose) {
        UUID r=UUID.randomUUID(),p=UUID.randomUUID();boolean initial=purpose.equals("INITIAL_ORDER");
        int goods=initial?20000:0,shipping=initial?5000:2000,fee=initial?300:0,total=goods+shipping+fee;
        j.update("""
            insert into payment_requests(id,organization_id,order_id,purpose,business_reference,expected_amount,goods_amount,shipping_amount,
                fee_amount,currency,acceptance_status,expires_at,created_at)
            values (?,?,?,?,?,?,?,?,?,'XOF','OPEN',now()+interval '30 minutes',now()-interval '1 minute')
            """,r,f.org(),f.order(),purpose,purpose+":"+r,total,goods,shipping,fee);
        j.update("""
            insert into payments(id,organization_id,payment_request_id,provider,provider_account_reference,merchant_reference,provider_transaction_id,
                provider_idempotency_key,initiation_key,expected_amount,currency,confirmed_amount,confirmed_currency,status,reconciliation_required,
                initiated_at,confirmed_at)
            values (?,?,?,'SIMULATED','fixture',?,?,?,?,?,'XOF',?,'XOF','SUCCEEDED',false,now()-interval '1 minute',now())
            """,p,f.org(),r,"GP-"+p,"TX-"+p,p.toString(),p.toString(),total,total);
        j.update("update payment_requests set acceptance_status='CLOSED',settled_payment_id=?,closed_at=now() where id=?",p,r);
        j.update("update orders set status='CONFIRMED',confirmed_at=now() where id=?",f.order());
        return r;
    }
    static UUID refund(JdbcTemplate j,Fixture f,String method) {
        UUID id=UUID.randomUUID();boolean platform=method.equals("BICTORYS_PAYOUT");
        j.update("""
            insert into payment_refunds(id,organization_id,order_id,calculation_id,amount,currency,purpose,method,external_method,status,
                business_reference,merchant_reference,provider,provider_account_reference,provider_transaction_id,provider_idempotency_key,
                recipient_phone_snapshot,recipient_name_snapshot,initiated_by,initiated_at,completed_at,reconciliation_required,manual_reference)
            values (?,?,?,?,500,'XOF','SHIPPING_CREDIT',?,'WAVE','SUCCEEDED',?,?,?,?,?,?,'+221771234567','Cliente Fixture',?,now(),now(),false,?)
            """,id,f.org(),f.order(),UUID.randomUUID(),method,"CREDIT:"+id,"GPR-"+id,platform?"BICTORYS":"MANUAL_EXTERNAL",
                platform?"sandbox":"internal",platform?"REFUND-"+id:null,UUID.randomUUID(),f.actor(),platform?null:"external-fixture");
        return id;
    }
}

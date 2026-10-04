package sn.groupia;

import static org.awaitility.Awaitility.await;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"groupia.customer-access.enabled=true","groupia.customer-access.public-base-url=https://groupia.test",
 "groupia.customer-access.magic-key-base64=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODk=",
 "groupia.customer-access.otp-pepper-base64=OTg3NjU0MzIxenl4d3Z1dHNycXBvbm1sa2ppaGdmZWRjYmE="})
@org.springframework.test.context.event.RecordApplicationEvents
@AutoConfigureMockMvc @Import({TestcontainersConfiguration.class,PaymentTestConfiguration.class,
 sn.groupia.payment.infrastructure.provider.bictorys.BictorysTestServerConfiguration.class,CustomerAccessIntegrationTests.DeliveryConfig.class})
class ShippingFinanceIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    private static String hash;
    private UUID actor, org, product, variant;
    private String jwt;
    private int customerSequence;
    private JsonNode campaign;

    @BeforeEach void setup() throws Exception {
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",Long.class)).isZero());
        jdbc.execute("TRUNCATE TABLE shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication");
        http.reset();clock.reset();delivery.links.clear();delivery.codes.clear();
        if(hash==null) hash=passwords.encode("Supplier fixture 2026!");
        actor=UUID.randomUUID();
        jdbc.update("insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?,'+221771234567',?,'ACTIVE',current_timestamp,current_timestamp)",actor,hash);
        jwt=token(actor);
        org=UUID.fromString(read(auth(post("/api/organizations"),Map.of("name","Boutique")).andExpect(status().isCreated()).andReturn()).path("id").asText());
        var p=product(org,Map.of("color","Noir","size","M"));
        product=UUID.fromString(p.path("id").asText());variant=UUID.fromString(p.path("variants").get(0).path("id").asText());
        campaign=createCampaign(org,variant);
        orderNumber=order(campaign,offer(),1,5000,0,"CONFIRMED");close();
        campaign=read(freeze(version()).andExpect(status().isCreated()).andReturn()).path("campaign");
        var r=mvc.perform(get(ACCESS+"/csrf")).andReturn();csrf=read(r).path("token").asText();csrfCookie=r.getResponse().getCookie("__Host-groupia_csrf");
        access.confirmed(org,orderId());
        var link=crypto.link(jdbc.queryForObject("select id from order_access_links where order_id=? and revoked_at is null",UUID.class,orderId()));
        cookie=postAccess("/magic/exchange",Map.of("token",link),null,null).andExpect(status().isOk()).andReturn().getResponse().getCookie("__Host-groupia_customer");
        http.reset();
    }





    @Autowired sn.groupia.payment.infrastructure.provider.bictorys.BictorysHttpStub http;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;
    @Autowired sn.groupia.payment.api.PaymentOperations payments;
    @Autowired sn.groupia.payment.application.service.PaymentService paymentService;
    @Autowired sn.groupia.customeraccess.application.service.CustomerAccess access;
    @Autowired sn.groupia.customeraccess.application.service.AccessCrypto crypto;
    @Autowired CustomerAccessIntegrationTests.FakeDelivery delivery;
    @Autowired org.springframework.test.context.event.ApplicationEvents events;
    @Autowired org.springframework.transaction.PlatformTransactionManager tx;
    @Autowired PaymentTestConfiguration.SimulatedProvider provider;
    private static final String ACCESS="/api/public/customer-access";
    private String trackingToken,orderNumber,csrf;
    private jakarta.servlet.http.Cookie cookie,csrfCookie;
    private UUID orderId(){return jdbc.queryForObject("select id from orders where order_number=?",UUID.class,orderNumber);}
    private String base(){return "/api/organizations/"+org+"/grouping-campaigns/"+campaignId();}
    private String finalPath(){return base()+"/orders/"+orderId()+"/shipping-finalizations";}
    private String payPath(){return "/orders/"+orderNumber+"/shipping-balance/payments";}
    private ResultActions finalizeAmount(int amount)throws Exception{
        long version=jdbc.queryForObject("select coalesce(max(version),0) from shipping_assessments",Long.class);
        return auth(post(finalPath()),Map.of("version",version,"origin","PROVIDER_QUOTED","quotedAmount",amount,
            "currency","XOF","source","FORWARDER","correctionReason","Montant corrigé"));
    }
    private JsonNode balance()throws Exception{
        return read(mvc.perform(get(ACCESS+"/orders/"+orderNumber).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("shippingSettlement");
    }
    private UUID requestId(){return jdbc.queryForObject("select id from payment_requests where purpose='SHIPPING_BALANCE' and acceptance_status='OPEN'",UUID.class);}
    private long requests(){return jdbc.queryForObject("select count(*) from payment_requests where purpose='SHIPPING_BALANCE'",Long.class);}
    private void queueCharge(){http.reply(202,"{\"type\":\"CheckoutLinkObject\",\"chargeId\":\""+UUID.randomUUID()+"\",\"link\":\"https://pay.bictorys.com/checkout\"}");}
    private ResultActions postAccess(String path,Object body,jakarta.servlet.http.Cookie session,String key)throws Exception{
        var r=post(ACCESS+path).cookie(csrfCookie).header("X-XSRF-TOKEN",csrf).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(session!=null)r.cookie(session);if(key!=null)r.header("Idempotency-Key",key);return mvc.perform(r);
    }
    private JsonNode start()throws Exception{queueCharge();return read(postAccess(payPath(),Map.of("provider","BICTORYS"),cookie,UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());}
    private void deliver(String paymentId,String state)throws Exception{
        var p=jdbc.queryForMap("select merchant_reference,payment_reference,expected_amount from payments where id=?",UUID.fromString(paymentId));
        mvc.perform(post("/api/payment/webhooks/bictorys").header("X-Secret-Key","fixture-webhook-secret").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("id","tx-"+paymentId,"merchantReference",p.get("merchant_reference"),"paymentReference",p.get("payment_reference"),
                "amount",p.get("expected_amount"),"currency","XOF","status",state,"timestamp",clock.instant().toString())))).andExpect(status().isAccepted());
    }
    @Test void notFinalizedHasNoObligation()throws Exception{
        assertThat(balance().path("status").asText()).isEqualTo("NOT_FINALIZED");assertThat(requests()).isZero();
        postAccess(payPath(),Map.of("provider","BICTORYS"),cookie,UUID.randomUUID().toString()).andExpect(status().isConflict());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"8000,BALANCE_DUE,5000,0,1","3000,SETTLED,0,0,0","2500,CREDIT_DUE,0,500,0"})
    void financialPositions(int amount,String status,int due,int credit,int count)throws Exception{
        finalizeAmount(amount).andExpect(status().isOk());var b=balance();
        assertThat(b.path("status").asText()).isEqualTo(status);assertThat(b.path("amountDue").asInt()).isEqualTo(due);
        assertThat(b.path("creditAmount").asInt()).isEqualTo(credit);assertThat(b.path("initialShippingPaidAmount").asInt()).isEqualTo(3000);
        assertThat(requests()).isEqualTo(count);
        if(count>0){var r=payments.request(org,requestId());assertThat(r.goodsAmount()).isZero();assertThat(r.feeAmount()).isZero();
            assertThat(r.shippingAmount()).isEqualByComparingTo("5000");assertThat(r.expiresAt()).isNull();
            new org.springframework.transaction.support.TransactionTemplate(tx).executeWithoutResult(t->assertThat(payments.createRequest(new sn.groupia.payment.api.dto.CreatePaymentRequest(org,orderId(),r.purpose(),r.businessReference(),r.expectedAmount(),r.currency(),r.goodsAmount(),r.feeAmount(),r.shippingAmount(),null)).id()).isEqualTo(r.id()));
            assertThat(requests()).isEqualTo(1);
        }
    }
    @Test void afterFinalAssessmentStartsFromZero()throws Exception{
        // Isolated historical fixture; no production data is modified.
        jdbc.update("update orders set initial_shipping_charge_amount=0,initial_amount_due=goods_subtotal+platform_fee_amount,shipping_terms_snapshot=jsonb_set(shipping_terms_snapshot,'{shippingCollectionMode}','\"AFTER_FINAL_ASSESSMENT\"') where id=?",orderId());
        finalizeAmount(8000).andExpect(status().isOk());assertThat(balance().path("amountDue").asInt()).isEqualTo(8000);
    }
    @Test void authenticatedBictorysSettlementIsIdempotentAndDoesNotChangeOrder()throws Exception{
        var before=jdbc.queryForMap("select status,fulfillment_status,confirmed_at from orders where id=?",orderId());
        finalizeAmount(8000).andExpect(status().isOk());var request=requestId();var attempt=start();
        assertThat(balance().path("status").asText()).isEqualTo("BALANCE_DUE");assertThat(balance().path("paymentInProgress").asBoolean()).isTrue();
        var sent=json.readTree(http.requests.getFirst().body());assertThat(sent.path("amount").asInt()).isEqualTo(5000);
        assertThat(sent.path("customerObject").path("name").asText()).isEqualTo("Awa Diop");
        String id=attempt.path("id").asText();deliver(id,"succeeded");deliver(id,"succeeded");
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(events.stream(sn.groupia.payment.api.event.PaymentRequestSettled.class).filter(e->e.purpose()==sn.groupia.payment.api.PaymentPurpose.SHIPPING_BALANCE).count()).isEqualTo(1));
        assertThat(balance().path("status").asText()).isEqualTo("SETTLED");assertThat(balance().path("additionalShippingPaidAmount").asInt()).isEqualTo(5000);
        assertThat(payments.request(org,request).settledPaymentId().toString()).isEqualTo(id);assertThat(payments.request(org,request).acceptanceStatus()).isEqualTo(sn.groupia.payment.api.AcceptanceStatus.CLOSED);
        assertThat(http.requests).hasSize(1);assertThat(jdbc.queryForMap("select status,fulfillment_status,confirmed_at from orders where id=?",orderId())).isEqualTo(before);
    }
    @Test void replacesUnpaidObligationAndBlocksOldInitiation()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());var old=requestId();finalizeAmount(7000).andExpect(status().isOk());
        assertThat(payments.request(org,old).acceptanceStatus()).isEqualTo(sn.groupia.payment.api.AcceptanceStatus.CANCELLED);
        assertThat(balance().path("amountDue").asInt()).isEqualTo(4000);assertThat(requests()).isEqualTo(2);
        assertThatThrownBy(()->payments.initiateRequest(org,old,"BICTORYS",UUID.randomUUID().toString(),null)).isInstanceOf(sn.groupia.shared.api.ApiException.class);
        assertThat(http.requests).isEmpty();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void pendingOrUncertainAttemptBlocksCorrectionAtomically(boolean uncertain)throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());if(uncertain)http.disconnect();else queueCharge();
        postAccess(payPath(),Map.of("provider","BICTORYS"),cookie,UUID.randomUUID().toString()).andExpect(status().isOk());
        var snapshot=jdbc.queryForList("select * from shipping_calculations");
        finalizeAmount(7000).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIPPING_BALANCE_PAYMENT_IN_PROGRESS"));
        assertThat(jdbc.queryForList("select * from shipping_calculations")).isEqualTo(snapshot);assertThat(requests()).isEqualTo(1);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"9000,BALANCE_DUE,1000,0","7500,CREDIT_DUE,0,500"})
    void correctionsAfterSettlementKeepRealHistory(int amount,String state,int due,int credit)throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());var p=start();deliver(p.path("id").asText(),"succeeded");
        var before=jdbc.queryForList("select * from payments");
        finalizeAmount(amount).andExpect(status().isOk());var b=balance();
        assertThat(b.path("status").asText()).isEqualTo(state);assertThat(b.path("amountDue").asInt()).isEqualTo(due);assertThat(b.path("creditAmount").asInt()).isEqualTo(credit);
        assertThat(b.path("totalShippingPaidAmount").asInt()).isEqualTo(8000);assertThat(jdbc.queryForList("select * from payments")).isEqualTo(before);
    }
    @Test void doubleClickAndConcurrentInitiationsUseOneProviderCall()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());String key=UUID.randomUUID().toString();queueCharge();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<String> call=()->read(postAccess(payPath(),Map.of("provider","BICTORYS"),cookie,key).andExpect(status().isOk()).andReturn()).path("id").asText();
            var a=pool.submit(call);var b=pool.submit(call);assertThat(a.get()).isEqualTo(b.get());
        }
        assertThat(http.requests).hasSize(1);assertThat(requests()).isEqualTo(1);
        postAccess(payPath(),Map.of("provider","BICTORYS"),cookie,UUID.randomUUID().toString()).andExpect(status().isConflict());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"failed","cancelled"})
    void failedAttemptDoesNotEraseDebtAndCanBeRetried(String state)throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());var first=start();deliver(first.path("id").asText(),state);
        assertThat(balance().path("amountDue").asInt()).isEqualTo(5000);assertThat(balance().path("paymentInProgress").asBoolean()).isFalse();
        var second=start();assertThat(second.path("id").asText()).isNotEqualTo(first.path("id").asText());assertThat(requests()).isEqualTo(1);
    }
    @Test void expiredAttemptCanBeRetriedWithoutExpiringTheObligation()throws Exception{
        provider.reset();finalizeAmount(8000).andExpect(status().isOk());var r=requestId();
        var p=payments.initiateRequest(org,r,"SIMULATED",UUID.randomUUID().toString(),null);
        String merchant=jdbc.queryForObject("select merchant_reference from payments where id=?",String.class,p.id());
        provider.status(merchant,sn.groupia.payment.api.PaymentStatus.EXPIRED);paymentService.reconcile(p.id());
        assertThat(balance().path("paymentInProgress").asBoolean()).isFalse();
        assertThat(payments.initiateRequest(org,r,"SIMULATED",UUID.randomUUID().toString(),null).id()).isNotEqualTo(p.id());
        assertThat(requests()).isEqualTo(1);
    }
    @Test void unknownBictorysStatusRemainsBlockedForReconciliation()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());var p=start();deliver(p.path("id").asText(),"expired");
        assertThat(balance().path("paymentInProgress").asBoolean()).isTrue();
        finalizeAmount(7000).andExpect(status().isConflict());
    }
    @Test void lateSuccessOnReplacedRequestIsReviewedAndBlocksNewPayment()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());var p=start();deliver(p.path("id").asText(),"failed");
        finalizeAmount(7000).andExpect(status().isOk());deliver(p.path("id").asText(),"succeeded");
        assertThat(balance().path("additionalShippingPaidAmount").asInt()).isZero();
        assertThat(balance().path("paymentInProgress").asBoolean()).isTrue();
        postAccess(payPath(),Map.of("provider","BICTORYS"),cookie,UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertThat(http.requests).hasSize(1);
    }
    @Test void simultaneousFinalizationsCreateOnlyOneObligation()throws Exception{
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var body=Map.of("version",0,"origin","PROVIDER_QUOTED","quotedAmount",8000,"currency","XOF","source","FORWARDER");
            java.util.concurrent.Callable<Integer> call=()->auth(post(finalPath()),body).andReturn().getResponse().getStatus();
            var a=pool.submit(call);var b=pool.submit(call);assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(requests()).isEqualTo(1);
    }
    @Test void rejectsForeignOrganizationAndUnsupportedAmounts()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());
        assertThatThrownBy(()->payments.initiateRequest(UUID.randomUUID(),requestId(),"BICTORYS",UUID.randomUUID().toString(),null))
            .isInstanceOf(sn.groupia.shared.api.ApiException.class);
        for(String field:List.of("purpose","orderId","organizationId","paymentRequestId","currency"))
            postAccess(payPath(),Map.of("provider","BICTORYS",field,"forged"),cookie,UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        assertThat(http.requests).isEmpty();
    }
    @Test void sessionCsrfScopeAndFinancialInputAreEnforced()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());String key=UUID.randomUUID().toString();
        postAccess(payPath(),Map.of("provider","BICTORYS"),null,key).andExpect(status().isUnauthorized());
        mvc.perform(post(ACCESS+payPath()).cookie(cookie).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"BICTORYS\"}")).andExpect(status().isForbidden());
        postAccess(payPath().replace(orderNumber,"GRP-999999999999"),Map.of("provider","BICTORYS"),cookie,key).andExpect(status().isNotFound());
        postAccess(payPath(),Map.of("provider","BICTORYS","amount",1),cookie,key).andExpect(status().isBadRequest());
        assertThat(http.requests).isEmpty();
    }
    @Test void phoneVerifiedCanPayOnlyOwnOrder()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());cookie=phone("+221779999999");
        postAccess(payPath(),Map.of("provider","BICTORYS"),cookie,UUID.randomUUID().toString()).andExpect(status().isNotFound());
        String own=jdbc.queryForObject("select customer_contact_snapshot->>'phoneE164' from orders where id=?",String.class,orderId());cookie=phone(own);start();assertThat(http.requests).hasSize(1);
    }
    private jakarta.servlet.http.Cookie phone(String phone)throws Exception{
        var c=read(postAccess("/otp/request",Map.of("phone",phone),null,null).andExpect(status().isOk()).andReturn());UUID id=UUID.fromString(c.path("challengeId").asText());
        return postAccess("/otp/verify",Map.of("challengeId",id,"code",delivery.codes.get(id)),null,null).andExpect(status().isOk()).andReturn().getResponse().getCookie("__Host-groupia_customer");
    }
    @Test void databaseFailureRollsBackCalculationAndCancellation()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());var old=requestId();var calculations=jdbc.queryForList("select * from shipping_calculations");
        // Force a DB failure only for the new obligation, after the coordinator has cancelled the old one.
        jdbc.execute("alter table payment_requests add constraint fixture_reject_shipping check (purpose<>'SHIPPING_BALANCE' or expected_amount<>4000)");
        try {finalizeAmount(7000).andExpect(status().isInternalServerError());}
        finally {jdbc.execute("alter table payment_requests drop constraint fixture_reject_shipping");}
        assertThat(jdbc.queryForList("select * from shipping_calculations")).isEqualTo(calculations);
        assertThat(payments.request(org,old).acceptanceStatus()).isEqualTo(sn.groupia.payment.api.AcceptanceStatus.OPEN);assertThat(requests()).isEqualTo(1);
    }
    @Test void noBusinessExpiryForShippingButInitialStillExpires()throws Exception{
        finalizeAmount(8000).andExpect(status().isOk());clock.advance(java.time.Duration.ofDays(400));
        queueCharge();var p=payments.initiateRequest(org,requestId(),"BICTORYS",UUID.randomUUID().toString(),new sn.groupia.payment.api.dto.PaymentCheckoutContext(orderNumber,"Awa Diop","+221770000001",null));
        deliver(p.id().toString(),"succeeded");assertThat(payments.request(org,payments.shippingRequests(org,orderId()).getFirst().request().id()).settledPaymentId()).isNotNull();
        assertThat(payments.initialRequest(org,orderId()).expiresAt()).isNotNull();
    }
    private UUID campaignId(){return UUID.fromString(campaign.path("id").asText());}
    private long version(){return campaign.path("version").asLong();}
    private void close() throws Exception {
        campaign=read(auth(post("/api/organizations/"+org+"/grouping-campaigns/"+campaignId()+"/close"),Map.of("version",version())).andExpect(status().isOk()).andReturn());
    }
    private String purchasePath(UUID owner){return "/api/organizations/"+owner+"/grouping-campaigns/"+campaignId()+"/supplier-order";}
    private ResultActions freeze(long version) throws Exception {return auth(post(purchasePath(org)),Map.of("campaignVersion",version));}
    private ResultActions frozen() throws Exception {return auth(get(purchasePath(org)));}
    private JsonNode product(UUID owner,Map<String,String> attrs) throws Exception {
        return read(auth(post("/api/organizations/"+owner+"/products"),Map.of("name","Chemise","description","Description historique",
            "variants",List.of(Map.of("attributes",attrs,"sku","SKU-"+UUID.randomUUID(),"saleUnit","UNIT")))).andExpect(status().isCreated()).andReturn());
    }
    private JsonNode createCampaign(UUID owner,UUID variantId) throws Exception {
        String base="/api/organizations/"+owner+"/grouping-campaigns";
        var c=read(auth(post(base),Map.of("title","Groupage","shippingTerms",Map.of("transportMode","AIR","shippingPricingMethod","FIXED","shippingCollectionMode","ESTIMATED_AT_CHECKOUT","shippingRate",3000))).andExpect(status().isCreated()).andReturn());
        c=read(auth(post(base+"/"+c.path("id").asText()+"/offers"),Map.of("campaignVersion",c.path("version").asLong(),"productVariantId",variantId,"unitPrice",5000,"commercialLabel","Même libellé")).andExpect(status().isOk()).andReturn());
        return read(auth(post(base+"/"+c.path("id").asText()+"/open"),Map.of("version",c.path("version").asLong())).andExpect(status().isOk()).andReturn());
    }
    private String order(JsonNode c,String offer,int quantity,int price,long revision,String state) throws Exception {
        var response=read(mvc.perform(post("/api/public/groupings/"+c.path("publicIdentifier").asText()+"/checkout")
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("contact",Map.of("firstName","Awa","lastName","Diop","phone","+22177"+String.format("%07d",++customerSequence)),
              "lines",List.of(Map.of("offerId",offer,"quantity",quantity,"expectedUnitPrice",price,"expectedPriceRevision",revision))))))
            .andExpect(status().isCreated()).andReturn());
        trackingToken=response.path("trackingToken").asText();
        String number=response.path("orderNumber").asText();
        queueCharge();
        var p=read(mvc.perform(post("/api/public/orders/"+number+"/payments").header("X-Order-Tracking-Token",trackingToken)
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"BICTORYS\"}"))
            .andExpect(status().isOk()).andReturn());
        deliver(p.path("id").asText(),"succeeded");
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("select status from orders where order_number=?",String.class,number)).isEqualTo("CONFIRMED"));
        return number;
    }
    private String offer(){return campaign.path("offers").get(0).path("id").asText();}
    private ResultActions auth(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request.header("Authorization","Bearer "+jwt));}
    private ResultActions auth(MockHttpServletRequestBuilder request,Object body) throws Exception{return auth(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    private JsonNode read(MvcResult result) throws Exception{return json.readTree(result.getResponse().getContentAsString());}
    private String token(UUID id){return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder().issuer("https://groupia.test").audience(List.of("groupia-api-test")).subject(id.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(1800)).build())).getTokenValue();}
}

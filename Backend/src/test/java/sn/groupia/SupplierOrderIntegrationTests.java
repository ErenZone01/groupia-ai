package sn.groupia;

import java.math.BigDecimal;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.util.AopTestUtils;
import static org.awaitility.Awaitility.await;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)
class SupplierOrderIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired EntityManagerFactory emf;
    @MockitoSpyBean NamedParameterJdbcTemplate summaryJdbc;
    @MockitoSpyBean OrganizationAccess access;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired org.springframework.modulith.events.FailedEventPublications failedPublications;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @MockitoSpyBean sn.groupia.grouping.api.SupplierCampaignLifecycle lifecycle;
    @MockitoSpyBean sn.groupia.procurement.domain.repository.SupplierOrders purchases;
    private static String hash;
    private UUID actor, org, product, variant;
    private String jwt;
    private int customerSequence;
    private JsonNode campaign;

    @BeforeEach void setup() throws Exception {
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",Long.class)).isZero());
        jdbc.execute("TRUNCATE TABLE order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication");
        if(hash==null) hash=passwords.encode("Supplier fixture 2026!");
        actor=UUID.randomUUID();
        jdbc.update("insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?,'+221771234567',?,'ACTIVE',current_timestamp,current_timestamp)",actor,hash);
        jwt=token(actor);
        org=UUID.fromString(read(auth(post("/api/organizations"),Map.of("name","Boutique")).andExpect(status().isCreated()).andReturn()).path("id").asText());
        var p=product(org,Map.of("color","Noir","size","M"));
        product=UUID.fromString(p.path("id").asText());variant=UUID.fromString(p.path("variants").get(0).path("id").asText());
        campaign=createCampaign(org,variant);
    }


    @ParameterizedTest @ValueSource(strings={"DRAFT","OPEN","SUPPLIER_ORDERED","IN_TRANSIT","ARRIVED","DISTRIBUTING","COMPLETED","CANCELLED"})
    void onlyClosedAllowsCreation(String state) throws Exception {
        jdbc.update("update grouping_campaigns set status=?,opened_at=case when ?='DRAFT' then null else opened_at end,cancelled_at=case when ?='CANCELLED' then current_timestamp else null end,cancellation_reason=case when ?='CANCELLED' then 'Test' else null end where id=?",state,state,state,state,campaignId());
        freeze(version()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_GROUPING_TRANSITION"));
        assertThat(countPurchases()).isZero();
    }
    @Test void beforeCreationReadIs404AndEmptyClosedCannotBeFrozen() throws Exception {
        frozen().andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_ORDER_NOT_FOUND"));
        close();
        freeze(version()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_ORDER_EMPTY"));
        assertClosed();
    }
    @Test void capturesAcceptedOnlyAndReturnsNewCampaignVersion() throws Exception {
        order(campaign,offer(),2,5000,0,"CONFIRMED");
        order(campaign,offer(),3,5000,0,"COMPLETED");
        order(campaign,offer(),7,5000,0,"EXPIRED");
        order(campaign,offer(),8,5000,0,"CANCELLED");
        close();long source=version();
        var result=read(freeze(source).andExpect(status().isCreated()).andReturn());
        var p=result.path("supplierOrder");
        assertThat(p.path("acceptedOrderCount").asLong()).isEqualTo(2);
        assertThat(p.path("totalUnits").asLong()).isEqualTo(5);
        assertThat(p.path("goodsAmount").decimalValue()).isEqualByComparingTo("25000");
        assertThat(p.path("currency").asText()).isEqualTo("XOF");
        assertThat(p.path("orderedAt").asText()).isNotBlank();
        assertThat(p.path("lines").get(0).path("productVariantId").asText()).isEqualTo(variant.toString());
        assertThat(p.path("lines").get(0).path("snapshot").path("variantAttributesSnapshot").path("color").asText()).isEqualTo("noir");
        assertThat(result.path("campaign").path("status").asText()).isEqualTo("SUPPLIER_ORDERED");
        assertThat(result.path("campaign").path("version").asLong()).isEqualTo(source+1);
        assertThat(p.toString()).doesNotContain("phone","trackingToken","acceptedOrderIds","orderedBy");
        assertThat(read(frozen().andExpect(status().isOk()).andReturn()).path("supplierOrder")).isEqualTo(p);
    }
    @Test void activePendingBlocksWithOnlyItsCount() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");
        order(campaign,offer(),1,5000,0,"PENDING_PAYMENT");
        order(campaign,offer(),1,5000,0,"PENDING_PAYMENT");close();
        var error=read(freeze(version()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUPPLIER_ORDER_PENDING_PAYMENTS"))
            .andExpect(jsonPath("$.details.pendingOrderCount").value(2)).andReturn());
        assertThat(error.toString()).doesNotContain("phone","customer","Awa","Diop","GRP-");
        assertClosed();
    }
    @Test void elapsedPendingDoesNotBlockEvenBeforeExpirationJob() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");
        var n=order(campaign,offer(),9,5000,0,"PENDING_PAYMENT");
        // Test fixture moves the whole date window into the past, keeping temporal constraints valid.
        jdbc.update("update orders set placed_at=placed_at-interval '2 hours',payment_deadline=payment_deadline-interval '2 hours' where order_number=?",n);
        close();
        freeze(version()).andExpect(status().isCreated()).andExpect(jsonPath("$.supplierOrder.totalUnits").value(1));
    }
    @Test void identicalRetryIsIdempotentAndNewIntentionIsRejected() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");close();long source=version();
        var first=read(freeze(source).andExpect(status().isCreated()).andReturn());
        var replay=read(freeze(source).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andReturn());
        assertThat(replay.path("supplierOrder")).isEqualTo(first.path("supplierOrder"));
        assertThat(countPurchases()).isEqualTo(1);
        freeze(first.path("campaign").path("version").asLong()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_ORDER_ALREADY_EXISTS"));
    }
    @Test void concurrentSameIntentionCreatesExactlyOnePurchase() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");close();long source=version();
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<MvcResult> call=()->{start.await();return freeze(source).andReturn();};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            var ra=a.get(20,java.util.concurrent.TimeUnit.SECONDS);var rb=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(ra.getResponse().getStatus(),rb.getResponse().getStatus())).containsExactlyInAnyOrder(200,201);
            assertThat(read(ra).path("supplierOrder")).isEqualTo(read(rb).path("supplierOrder"));
            assertThat(countPurchases()).isEqualTo(1);
        }
    }
    @Test void staleVersionDoesNotCreateAnything() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");close();
        freeze(version()-1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GROUPING_CAMPAIGN_VERSION_CONFLICT"));
        assertClosed();
    }
    @Test void catalogAndLiveProjectionChangesNeverRewritePurchase() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");
        var pending=order(campaign,offer(),4,5000,0,"PENDING_PAYMENT");
        jdbc.update("update orders set placed_at=placed_at-interval '2 hours',payment_deadline=payment_deadline-interval '2 hours' where order_number=?",pending);
        close();var original=read(freeze(version()).andReturn()).path("supplierOrder");
        jdbc.update("update products set name='Renommé après achat' where id=?",product);
        jdbc.update("update grouping_offers set unit_price=9000,price_revision=price_revision+1 where id=?",UUID.fromString(offer()));
        jdbc.update("update orders set status='CONFIRMED',confirmed_at=placed_at where order_number=?",pending);
        summary().andExpect(jsonPath("$.totalUnits").value(5));
        clearInvocations(summaryJdbc);
        assertThat(read(frozen().andReturn()).path("supplierOrder")).isEqualTo(original);
        verifyNoInteractions(summaryJdbc); // read does not recalculate the live aggregate
    }
    @Test void lateAcceptanceIsDurableIdempotentAndDoesNotChangeFrozenLines() throws Exception {
        String accepted=order(campaign,offer(),1,5000,0,"CONFIRMED");
        String late=order(campaign,offer(),2,5000,0,"EXPIRED");
        close();var original=read(freeze(version()).andReturn()).path("supplierOrder");
        var lateId=jdbc.queryForObject("select id from orders where order_number=?",UUID.class,late);
        var event=new sn.groupia.ordering.api.event.OrderCommerciallyAccepted(UUID.randomUUID(),Instant.now(),org,campaignId(),lateId);
        jdbc.update("update orders set status='CONFIRMED',confirmed_at=placed_at where id=?",lateId);
        publish(event);
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("select count(*) from supplier_order_anomalies",Long.class)).isEqualTo(1));
        publish(event);
        var included=jdbc.queryForObject("select id from orders where order_number=?",UUID.class,accepted);
        publish(new sn.groupia.ordering.api.event.OrderCommerciallyAccepted(UUID.randomUUID(),Instant.now(),org,campaignId(),included));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->{
            var read=read(frozen().andExpect(status().isOk()).andReturn());
            assertThat(read.path("lateAcceptedOrderCount").asLong()).isEqualTo(1);
            assertThat(read.path("supplierOrder")).isEqualTo(original);
        });
    }
    @Test void failedLateAcceptancePublicationRemainsRecoverable() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");
        String late=order(campaign,offer(),1,5000,0,"EXPIRED");
        close();var original=read(freeze(version()).andReturn()).path("supplierOrder");
        UUID lateId=jdbc.queryForObject("select id from orders where order_number=?",UUID.class,late);
        jdbc.update("update orders set status='CONFIRMED',confirmed_at=placed_at where id=?",lateId);
        var failure=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{if(failure.getAndSet(false))throw new IllegalStateException("transient anomaly persistence failure"); return i.callRealMethod();})
            .when(purchases).flagLateAcceptance(any(),any(),any(),any(),any());
        publish(new sn.groupia.ordering.api.event.OrderCommerciallyAccepted(UUID.randomUUID(),Instant.now(),org,campaignId(),lateId));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("select count(*) from event_publication where status='FAILED'",Long.class)).isEqualTo(1));
        assertThat(read(frozen().andReturn()).path("supplierOrder")).isEqualTo(original);
        failedPublications.resubmit(ResubmissionOptions.defaults().withMinAge(java.time.Duration.ZERO));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(read(frozen().andReturn()).path("lateAcceptedOrderCount").asLong()).isEqualTo(1));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is null",Long.class)).isZero());
    }
    @Test void snapshotAndTransitionRollbackTogetherWhenTransitionFails() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");close();
        doThrow(new IllegalStateException("test rollback")).when(AopTestUtils.<sn.groupia.grouping.api.SupplierCampaignLifecycle>getUltimateTargetObject(lifecycle)).ordered(eq(org),eq(campaignId()),anyLong(),any());
        freeze(version()).andExpect(status().isInternalServerError());assertClosed();
    }
    @Test void transitionRollsBackOnLateTransactionFailure() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");close();
        doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("after transition");})
            .when(AopTestUtils.<sn.groupia.grouping.api.SupplierCampaignLifecycle>getUltimateTargetObject(lifecycle)).ordered(eq(org),eq(campaignId()),anyLong(),any());
        freeze(version()).andExpect(status().isInternalServerError());assertClosed();
    }
    @Test void insertionFailureLeavesCampaignClosed() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");close();
        doThrow(new IllegalStateException("insert unavailable")).when(purchases).insert(any());
        freeze(version()).andExpect(status().isInternalServerError());assertClosed();
    }
    @Test void immutableDatabaseDocumentCannotBeUpdatedOrDeleted() throws Exception {
        order(campaign,offer(),1,5000,0,"CONFIRMED");close();freeze(version()).andExpect(status().isCreated());
        assertThatThrownBy(()->jdbc.update("update supplier_orders set goods_amount=goods_amount+1")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from supplier_orders")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void crossOrganizationAndMissingPermissionAreDenied() throws Exception {
        var other=UUID.fromString(read(auth(post("/api/organizations"),Map.of("name","Autre")).andReturn()).path("id").asText());
        auth(get(purchasePath(other))).andExpect(status().isNotFound());
        auth(post(purchasePath(other)),Map.of("campaignVersion",version())).andExpect(status().isNotFound());
        mvc.perform(post(purchasePath(org)).contentType(MediaType.APPLICATION_JSON).content("{\"campaignVersion\":0}")).andExpect(status().isUnauthorized());
        jdbc.update("update organization_members set role='VIEWER' where organization_id=?",org);
        freeze(version()).andExpect(status().isForbidden());
        jdbc.update("update organization_members set status='REVOKED',revoked_at=current_timestamp where organization_id=?",org);
        frozen().andExpect(status().isForbidden());
    }
    private void publish(sn.groupia.ordering.api.event.OrderCommerciallyAccepted e){transaction.executeWithoutResult(s->events.publishEvent(e));}
    private UUID campaignId(){return UUID.fromString(campaign.path("id").asText());}
    private long version(){return campaign.path("version").asLong();}
    private void close() throws Exception {
        campaign=read(auth(post("/api/organizations/"+org+"/grouping-campaigns/"+campaignId()+"/close"),Map.of("version",version())).andExpect(status().isOk()).andReturn());
    }
    private String purchasePath(UUID owner){return "/api/organizations/"+owner+"/grouping-campaigns/"+campaignId()+"/supplier-order";}
    private ResultActions freeze(long version) throws Exception {return auth(post(purchasePath(org)),Map.of("campaignVersion",version));}
    private ResultActions frozen() throws Exception {return auth(get(purchasePath(org)));}
    private long countPurchases(){return jdbc.queryForObject("select count(*) from supplier_orders",Long.class);}
    private void assertClosed(){
        assertThat(countPurchases()).isZero();
        assertThat(jdbc.queryForObject("select status from grouping_campaigns where id=?",String.class,campaignId())).isEqualTo("CLOSED");
    }
    private JsonNode product(UUID owner,Map<String,String> attrs) throws Exception {
        return read(auth(post("/api/organizations/"+owner+"/products"),Map.of("name","Chemise","description","Description historique",
            "variants",List.of(Map.of("attributes",attrs,"sku","SKU-"+UUID.randomUUID(),"saleUnit","UNIT")))).andExpect(status().isCreated()).andReturn());
    }
    private JsonNode createCampaign(UUID owner,UUID variantId) throws Exception {
        String base="/api/organizations/"+owner+"/grouping-campaigns";
        var c=read(auth(post(base),Map.of("title","Groupage","shippingTerms",Map.of("transportMode","AIR","shippingPricingMethod","FIXED","shippingCollectionMode","ESTIMATED_AT_CHECKOUT","shippingRate",1000))).andExpect(status().isCreated()).andReturn());
        c=read(auth(post(base+"/"+c.path("id").asText()+"/offers"),Map.of("campaignVersion",c.path("version").asLong(),"productVariantId",variantId,"unitPrice",5000,"commercialLabel","Même libellé")).andExpect(status().isOk()).andReturn());
        return read(auth(post(base+"/"+c.path("id").asText()+"/open"),Map.of("version",c.path("version").asLong())).andExpect(status().isOk()).andReturn());
    }
    private String addOffer(UUID variantId) throws Exception {
        campaign=read(auth(post("/api/organizations/"+org+"/grouping-campaigns/"+campaign.path("id").asText()+"/offers"),Map.of("campaignVersion",campaign.path("version").asLong(),"productVariantId",variantId,"unitPrice",5000,"commercialLabel","Même libellé")).andExpect(status().isOk()).andReturn());
        return campaign.path("offers").get(campaign.path("offers").size()-1).path("id").asText();
    }
    private String order(JsonNode c,String offer,int quantity,int price,long revision,String state) throws Exception {
        var response=read(mvc.perform(post("/api/public/groupings/"+c.path("publicIdentifier").asText()+"/checkout")
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("contact",Map.of("firstName","Awa","lastName","Diop","phone","+22177"+String.format("%07d",++customerSequence)),
              "lines",List.of(Map.of("offerId",offer,"quantity",quantity,"expectedUnitPrice",price,"expectedPriceRevision",revision))))))
            .andExpect(status().isCreated()).andReturn());
        String number=response.path("orderNumber").asText();
        // Testcontainer-only fixtures for states whose fulfilment APIs are not part of this slice.
        jdbc.update("update orders set status=?, confirmed_at=case when ? in ('CONFIRMED','COMPLETED') then placed_at else null end, completed_at=case when ?='COMPLETED' then placed_at else null end where order_number=?",state,state,state,number);
        return number;
    }
    private String offer(){return campaign.path("offers").get(0).path("id").asText();}
    private String path(UUID owner,String id){return "/api/organizations/"+owner+"/grouping-campaigns/"+id+"/supplier-summary";}
    private ResultActions summary() throws Exception {return auth(get(path(org,campaign.path("id").asText())));}
    private ResultActions auth(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request.header("Authorization","Bearer "+jwt));}
    private ResultActions auth(MockHttpServletRequestBuilder request,Object body) throws Exception{return auth(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    private JsonNode read(MvcResult result) throws Exception{return json.readTree(result.getResponse().getContentAsString());}
    private String token(UUID id){return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder().issuer("https://groupia.test").audience(List.of("groupia-api-test")).subject(id.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(1800)).build())).getTokenValue();}
}

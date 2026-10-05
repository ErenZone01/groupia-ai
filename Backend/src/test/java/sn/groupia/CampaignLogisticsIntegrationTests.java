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

@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)
class CampaignLogisticsIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @MockitoSpyBean sn.groupia.grouping.domain.repository.GroupingChanges changes;
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
        orderNumber=order(campaign,offer(),1,5000,0,"CONFIRMED");close();
        campaign=read(freeze(version()).andExpect(status().isCreated()).andReturn()).path("campaign");
        departed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }



    @Test void departureArrivalHistoryAndPublicProgressPreserveFinancialState() throws Exception {
        var financial=jdbc.queryForMap("select status,fulfillment_status,estimated_shipping_amount,initial_amount_due from orders where order_number=?",orderNumber);
        var supplier=read(frozen().andReturn()).path("supplierOrder");
        long initial=version();
        var result=read(ship(initial).andExpect(status().isOk()).andReturn());
        assertThat(result.path("campaign").path("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(result.path("campaign").path("version").asLong()).isEqualTo(initial+1);
        var s=result.path("shipment");
        assertThat(s.path("transportMode").asText()).isEqualTo("AIR");
        assertThat(Instant.parse(s.path("departedAt").asText())).isEqualTo(departed);
        assertThat(s.path("trackingReference").asText()).isEqualTo("INTERNAL-REF");
        assertThat(read(auth(get(base()+"/shipment")).andReturn()).path("shipment")).isEqualTo(s);
        var publicResult=read(track().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED")).andReturn());
        assertThat(publicResult.path("groupingProgress").path("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(publicResult.toString()).doesNotContain("INTERNAL-REF","Note interne","trackingToken","organizationId","trackingReference");
        Instant arrived=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var arrival=read(auth(post(base()+"/arrive"),Map.of("version",initial+1,"arrivedAt",arrived.toString())).andExpect(status().isOk()).andReturn());
        assertThat(arrival.path("campaign").path("status").asText()).isEqualTo("ARRIVED");
        assertThat(arrival.path("campaign").path("version").asLong()).isEqualTo(initial+2);
        assertThat(Instant.parse(arrival.path("shipment").path("arrivedAt").asText())).isEqualTo(arrived);
        track().andExpect(jsonPath("$.groupingProgress.status").value("ARRIVED")).andExpect(jsonPath("$.fulfillmentStatus").value("NOT_READY"));
        assertThat(read(frozen().andReturn()).path("supplierOrder")).isEqualTo(supplier);
        assertThat(jdbc.queryForMap("select status,fulfillment_status,estimated_shipping_amount,initial_amount_due from orders where order_number=?",orderNumber)).isEqualTo(financial);
        assertThat(jdbc.queryForObject("select count(*) from payment_requests where purpose='SHIPPING_BALANCE'",Long.class)).isZero();
        var history=jdbc.queryForList("select from_status,to_status,campaign_version,actor_id from grouping_changes where kind='LOGISTICS_TRANSITION' order by campaign_version");
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("from_status","SUPPLIER_ORDERED").containsEntry("to_status","IN_TRANSIT").containsEntry("actor_id",actor).containsEntry("campaign_version",initial+1);
        assertThat(history.get(1)).containsEntry("from_status","IN_TRANSIT").containsEntry("to_status","ARRIVED").containsEntry("campaign_version",initial+2);
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","OPEN","CLOSED","IN_TRANSIT","ARRIVED","DISTRIBUTING","COMPLETED","CANCELLED"})
    void invalidDepartureStateRejected(String state) throws Exception {
        state(state);ship(version()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_GROUPING_TRANSITION"));
        assertThat(countShipments()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","OPEN","CLOSED","SUPPLIER_ORDERED","ARRIVED","DISTRIBUTING","COMPLETED","CANCELLED"})
    void invalidArrivalStateRejected(String state) throws Exception {
        state(state);auth(post(base()+"/arrive"),Map.of("version",version())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_GROUPING_TRANSITION"));
    }
    @Test void optionalFieldsAndUnknownArrivalSupported() throws Exception {
        var result=read(auth(post(base()+"/ship"),Map.of("version",version(),"departedAt",departed.toString())).andExpect(status().isOk()).andReturn());
        assertThat(result.path("shipment").path("expectedArrivalAt").isNull()).isTrue();
        auth(post(base()+"/arrive"),Map.of("version",version()+1)).andExpect(status().isOk()).andExpect(jsonPath("$.shipment.arrivedAt").doesNotExist()).andExpect(jsonPath("$.shipment.arrivalConfirmedAt").exists());
    }
    @Test void staleAndReplayCannotRewriteDepartureOrArrival() throws Exception {
        ship(version()-1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GROUPING_CAMPAIGN_VERSION_CONFLICT"));
        var first=read(ship(version()).andExpect(status().isOk()).andReturn()).path("shipment");
        ship(version()).andExpect(status().isConflict());ship(version()+1).andExpect(status().isConflict());
        assertThat(read(auth(get(base()+"/shipment")).andReturn()).path("shipment")).isEqualTo(first);
        var arrived=read(auth(post(base()+"/arrive"),Map.of("version",version()+1)).andExpect(status().isOk()).andReturn());
        auth(post(base()+"/arrive"),Map.of("version",version()+1)).andExpect(status().isConflict());
        auth(post(base()+"/arrive"),Map.of("version",version()+2)).andExpect(status().isConflict());
        assertThat(read(auth(get(base()+"/shipment")).andReturn()).path("shipment")).isEqualTo(arrived.path("shipment"));
        assertThat(historyCount()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"ship","arrive"})
    void concurrentTransitionHasOneWinner(String action) throws Exception {
        if(action.equals("arrive"))ship(version()).andExpect(status().isOk());
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<Integer> call=()->{start.await();return (action.equals("ship")?ship(version()):auth(post(base()+"/arrive"),Map.of("version",version()+1))).andReturn().getResponse().getStatus();};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            assertThat(List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(countShipments()).isEqualTo(1);assertThat(historyCount()).isEqualTo(action.equals("ship")?1:2);
    }
    @Test void invalidDatesAndFieldsLeaveNoPartialTransition() throws Exception {
        auth(post(base()+"/ship"),Map.of()).andExpect(status().isBadRequest());
        auth(post(base()+"/ship"),Map.of("version",version(),"departedAt",Instant.now().plusSeconds(3600).toString())).andExpect(status().isBadRequest());
        auth(post(base()+"/ship"),Map.of("version",version(),"departedAt",departed.toString(),"expectedArrivalAt",departed.minusSeconds(10).toString())).andExpect(status().isBadRequest());
        auth(post(base()+"/ship"),Map.of("version",version(),"departedAt",departed.toString(),"note","x".repeat(2001))).andExpect(status().isBadRequest());
        assertThat(countShipments()).isZero();assertThat(historyCount()).isZero();assertThat(state()).isEqualTo("SUPPLIER_ORDERED");
        ship(version()).andExpect(status().isOk());
        auth(post(base()+"/arrive"),Map.of("version",version()+1,"arrivedAt",departed.minusSeconds(1).toString())).andExpect(status().isBadRequest());
        auth(post(base()+"/arrive"),Map.of("version",version()+1,"arrivedAt",Instant.now().plusSeconds(3600).toString())).andExpect(status().isBadRequest());
        assertThat(state()).isEqualTo("IN_TRANSIT");
    }
    @Test void privateLogisticsRequiresPermissionAndScopedOwnership() throws Exception {
        mvc.perform(post(base()+"/ship").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(version())))).andExpect(status().isUnauthorized());
        mvc.perform(get(base()+"/shipment")).andExpect(status().isUnauthorized());
        UUID other=UUID.fromString(read(auth(post("/api/organizations"),Map.of("name","Autre")).andReturn()).path("id").asText());
        auth(post("/api/organizations/"+other+"/grouping-campaigns/"+campaignId()+"/ship"),body(version())).andExpect(status().isNotFound());
        auth(get("/api/organizations/"+other+"/grouping-campaigns/"+campaignId()+"/shipment")).andExpect(status().isNotFound());
        auth(post("/api/organizations/"+UUID.randomUUID()+"/grouping-campaigns/"+campaignId()+"/arrive"),Map.of("version",version())).andExpect(status().isForbidden());
        jdbc.update("update organization_members set role='VIEWER' where organization_id=?",org);
        ship(version()).andExpect(status().isForbidden());auth(post(base()+"/arrive"),Map.of("version",version())).andExpect(status().isForbidden());
        auth(get(base()+"/shipment")).andExpect(status().isOk());
        jdbc.update("update organization_members set status='REVOKED',revoked_at=current_timestamp where organization_id=?",org);
        auth(get(base()+"/shipment")).andExpect(status().isForbidden());
    }
    @Test void tokenIsRequiredAndCannotBeReplacedByPublicId() throws Exception {
        mvc.perform(get("/api/public/orders/track")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/orders/track").header("X-Order-Tracking-Token",campaignId().toString())).andExpect(status().isNotFound());
        track().andExpect(status().isOk()).andExpect(jsonPath("$.groupingProgress.status").value("SUPPLIER_ORDERED"));
    }
    @ParameterizedTest @ValueSource(strings={"ship","arrive"})
    void historyFailureRollsBackShipmentAndCampaign(String action) throws Exception {
        if(action.equals("arrive"))ship(version()).andExpect(status().isOk());
        doThrow(new IllegalStateException("history unavailable")).when(changes).append(any());
        (action.equals("ship")?ship(version()):auth(post(base()+"/arrive"),Map.of("version",version()+1))).andExpect(status().isInternalServerError());
        assertThat(state()).isEqualTo(action.equals("ship")?"SUPPLIER_ORDERED":"IN_TRANSIT");
        if(action.equals("ship"))assertThat(countShipments()).isZero();
        else assertThat(jdbc.queryForObject("select arrived_at from shipments",java.sql.Timestamp.class)).isNull();
    }
    @Test void databasePreservesFirstDatesAndHistory() throws Exception {
        ship(version()).andExpect(status().isOk());
        assertThatThrownBy(()->jdbc.update("update shipments set departed_at=departed_at-interval '1 day'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from shipments")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        auth(post(base()+"/arrive"),Map.of("version",version()+1)).andExpect(status().isOk());
        assertThatThrownBy(()->jdbc.update("update shipments set arrived_at=departed_at")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from grouping_changes where kind='LOGISTICS_TRANSITION'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private String trackingToken,orderNumber;
    private Instant departed;
    private String base(){return "/api/organizations/"+org+"/grouping-campaigns/"+campaignId();}

    @Test void emptyDossierCanExistBeforeDepartureAndRetainsVersion() throws Exception {
        var result=read(auth(put(base()+"/shipment"),Map.of("version",version())).andExpect(status().isOk()).andReturn());
        var s=result.path("shipment");
        assertThat(result.path("campaign").path("status").asText()).isEqualTo("SUPPLIER_ORDERED");
        assertThat(s.path("departureConfirmedAt").isNull()).isTrue();
        assertThat(s.path("providerName").isNull()).isTrue();
        assertThat(s.path("trackingReference").isNull()).isTrue();
        assertThat(s.path("timeline").size()).isEqualTo(1);
        assertThat(s.path("timeline").get(0).path("actorId").asText()).isEqualTo(actor.toString());
        assertThat(s.path("timeline").get(0).path("source").asText()).isEqualTo("UNKNOWN");
        auth(put(base()+"/shipment"),Map.of("version",version())).andExpect(status().isConflict());
    }
    @Test void unknownDatesRemainNullDespiteConfirmedTransitions() throws Exception {
        var first=read(auth(post(base()+"/ship"),Map.of("version",version(),"source","FORWARDER")).andExpect(status().isOk()).andReturn());
        var s=first.path("shipment");
        assertThat(s.path("departedAt").isNull()).isTrue();
        assertThat(s.path("departureConfirmedAt").isTextual()).isTrue();
        assertThat(s.path("timeline").get(0).path("occurredAt").isNull()).isTrue();
        var arrival=read(auth(post(base()+"/arrive"),Map.of("version",version()+1)).andExpect(status().isOk()).andReturn()).path("shipment");
        assertThat(arrival.path("arrivedAt").isNull()).isTrue();
        assertThat(arrival.path("arrivalConfirmedAt").isTextual()).isTrue();
        track().andExpect(jsonPath("$.groupingProgress.status").value("ARRIVED"))
            .andExpect(jsonPath("$.groupingProgress.departedAt").doesNotExist())
            .andExpect(jsonPath("$.groupingProgress.arrivedAt").doesNotExist())
            .andExpect(jsonPath("$.fulfillmentStatus").value("NOT_READY"));
        assertThat(jdbc.queryForObject("select count(*) from payment_requests where purpose='SHIPPING_BALANCE'",Long.class)).isZero();
    }
    @Test void providerEditsAndEtaChangesKeepPrivateSnapshots() throws Exception {
        long v=version();
        auth(put(base()+"/shipment"),Map.of("version",v,"actorId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        auth(put(base()+"/shipment"),Map.of("version",v,"providerName","Prestataire privé","providerContact","+221770000001",
            "trackingReference","REF-SECRET","note","Note confidentielle","source","SUPPLIER"))
            .andExpect(status().isOk());
        String eta=Instant.now().plusSeconds(86400).toString(),next=Instant.now().plusSeconds(172800).toString();
        auth(put(base()+"/shipment/eta"),Map.of("version",v+1,"expectedArrivalAt",eta,"source","FORWARDER")).andExpect(status().isOk());
        var result=read(auth(put(base()+"/shipment/eta"),Map.of("version",v+2,"expectedArrivalAt",next)).andExpect(status().isOk()).andReturn()).path("shipment");
        assertThat(result.path("timeline").size()).isEqualTo(3);
        assertThat(Instant.parse(result.path("timeline").get(2).path("previousExpectedArrivalAt").asText()))
            .isEqualTo(Instant.parse(eta).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(result.path("timeline").get(0).path("actorId").asText()).isEqualTo(actor.toString());
        auth(put(base()+"/shipment"),Map.of("version",v+3)).andExpect(status().isOk())
            .andExpect(jsonPath("$.shipment.providerName").doesNotExist())
            .andExpect(jsonPath("$.shipment.timeline[0].providerName").value("Prestataire privé"));
        auth(post(base()+"/ship"),Map.of("version",v+4)).andExpect(status().isOk())
            .andExpect(jsonPath("$.shipment.expectedArrivalAt").exists());
        String publicBody=track().andReturn().getResponse().getContentAsString();
        assertThat(publicBody).doesNotContain("Prestataire privé","+221770000001","REF-SECRET","Note confidentielle","actorId","timeline","providerContact");
        auth(put(base()+"/shipment/eta"),Map.of("version",v+5)).andExpect(status().isOk())
            .andExpect(jsonPath("$.shipment.expectedArrivalAt").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings={"FORWARDER_RECEIVED","CUSTOMS_STARTED","CUSTOMS_COMPLETED","AVAILABLE_AT_FORWARDER","COLLECTED_BY_MANAGER","INFORMATION"})
    void optionalFactsDoNotForceStatusOrFinancialChanges(String type) throws Exception {
        var result=read(auth(post(base()+"/shipment/facts"),Map.of("version",version(),"type",type,"note","Information reçue","source","DOCUMENT"))
            .andExpect(status().isOk()).andReturn());
        assertThat(result.path("campaign").path("status").asText()).isEqualTo("SUPPLIER_ORDERED");
        var fact=result.path("shipment").path("timeline").get(0);
        assertThat(fact.path("source").asText()).isEqualTo("DOCUMENT");
        assertThat(fact.path("occurredAt").isNull()).isTrue();
        assertThat(fact.path("recordedAt").isTextual()).isTrue();
        assertThat(jdbc.queryForObject("select fulfillment_status from orders where order_number=?",String.class,orderNumber)).isEqualTo("NOT_READY");
        auth(post(base()+"/shipment/facts"),Map.of("version",version(),"type",type,"note","Information reçue")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from shipment_facts",Integer.class)).isEqualTo(1);
    }
    @Test void supplementaryEndpointCannotForgeTransitionAndRejectsFutureFacts() throws Exception {
        for(String type:List.of("DEPARTURE_CONFIRMED","ARRIVAL_CONFIRMED","ETA_UPDATED","DOSSIER_UPDATED"))
            auth(post(base()+"/shipment/facts"),Map.of("version",version(),"type",type)).andExpect(status().isBadRequest());
        auth(post(base()+"/shipment/facts"),Map.of("version",version(),"type","FORWARDER_RECEIVED","occurredAt",Instant.now().plusSeconds(3600).toString())).andExpect(status().isBadRequest());
        auth(post(base()+"/shipment/facts"),Map.of("version",version(),"type","INFORMATION")).andExpect(status().isBadRequest());
        assertThat(countShipments()).isZero();
    }
    @Test void enrichmentAndTimelineAreScopedAndPermissionProtected() throws Exception {
        UUID other=UUID.fromString(read(auth(post("/api/organizations"),Map.of("name","Autre")).andReturn()).path("id").asText());
        auth(put("/api/organizations/"+other+"/grouping-campaigns/"+campaignId()+"/shipment"),Map.of("version",version())).andExpect(status().isNotFound());
        jdbc.update("update organization_members set role='VIEWER' where organization_id=?",org);
        auth(put(base()+"/shipment"),Map.of("version",version())).andExpect(status().isForbidden());
        auth(put(base()+"/shipment/eta"),Map.of("version",version())).andExpect(status().isForbidden());
        auth(post(base()+"/shipment/facts"),Map.of("version",version(),"type","FORWARDER_RECEIVED")).andExpect(status().isForbidden());
        assertThat(countShipments()).isZero();
    }
    @Test void concurrentDossierChangesHaveOneWinnerAndOneFact() throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<Integer> call=()->{start.await();return auth(put(base()+"/shipment"),Map.of("version",version(),"providerName","Prestataire")).andReturn().getResponse().getStatus();};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            assertThat(List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from shipment_facts",Integer.class)).isEqualTo(1);
    }
    @Test void timelineCannotBeEditedOrDeletedAndDossierCannotStartTooEarly() throws Exception {
        state("CLOSED");auth(put(base()+"/shipment"),Map.of("version",version())).andExpect(status().isConflict());
        state("SUPPLIER_ORDERED");
        auth(put(base()+"/shipment"),Map.of("version",version())).andExpect(status().isOk());
        assertThatThrownBy(()->jdbc.update("update shipment_facts set note='rewrite'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from shipment_facts")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Map<String,Object> body(long version){return Map.of("version",version,"departedAt",departed.toString(),"expectedArrivalAt",departed.plusSeconds(86400).toString(),"trackingReference","INTERNAL-REF","note","Note interne");}
    private ResultActions ship(long v)throws Exception{return auth(post(base()+"/ship"),body(v));}
    private ResultActions track()throws Exception{return mvc.perform(get("/api/public/orders/track").header("X-Order-Tracking-Token",trackingToken));}
    private long countShipments(){return jdbc.queryForObject("select count(*) from shipments",Long.class);}
    private long historyCount(){return jdbc.queryForObject("select count(*) from grouping_changes where kind='LOGISTICS_TRANSITION'",Long.class);}
    private String state(){return jdbc.queryForObject("select status from grouping_campaigns where id=?",String.class,campaignId());}
    private void state(String state){
        jdbc.update("update grouping_campaigns set status=?,closed_at=case when ?='DRAFT' then null else closed_at end,opened_at=case when ?='DRAFT' then null else opened_at end,cancelled_at=case when ?='CANCELLED' then current_timestamp else null end,cancellation_reason=case when ?='CANCELLED' then 'Test' else null end where id=?",state,state,state,state,state,campaignId());
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
        var c=read(auth(post(base),Map.of("title","Groupage","shippingTerms",Map.of("transportMode","AIR","shippingPricingMethod","FIXED","shippingCollectionMode","ESTIMATED_AT_CHECKOUT","shippingRate",1000))).andExpect(status().isCreated()).andReturn());
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
        // Testcontainer-only fixtures for states whose fulfilment APIs are not part of this slice.
        jdbc.update("update orders set status=?, confirmed_at=case when ? in ('CONFIRMED','COMPLETED') then placed_at else null end, completed_at=case when ?='COMPLETED' then placed_at else null end where order_number=?",state,state,state,number);
        return number;
    }
    private String offer(){return campaign.path("offers").get(0).path("id").asText();}
    private ResultActions auth(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request.header("Authorization","Bearer "+jwt));}
    private ResultActions auth(MockHttpServletRequestBuilder request,Object body) throws Exception{return auth(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    private JsonNode read(MvcResult result) throws Exception{return json.readTree(result.getResponse().getContentAsString());}
    private String token(UUID id){return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder().issuer("https://groupia.test").audience(List.of("groupia-api-test")).subject(id.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(1800)).build())).getTokenValue();}
}

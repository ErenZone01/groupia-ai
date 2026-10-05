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
class ShippingAssessmentIntegrationTests {
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




 private String trackingToken,orderNumber; private Instant departed;
 private String base(){return "/api/organizations/"+org+"/grouping-campaigns/"+campaignId();}
 private UUID orderId(){return jdbc.queryForObject("select id from orders where order_number=?",UUID.class,orderNumber);}
 private String shipping(){return base()+"/orders/"+orderId();}
 private JsonNode measure(long v,Map<String,Object> i)throws Exception{return read(auth(post(shipping()+"/shipping-measurements"),Map.of("version",v,"information",i)).andExpect(status().isOk()).andReturn());}
 private Map<String,Object> calculated(long v,JsonNode m){return Map.of("version",v,"origin","CALCULATED","measurementId",m.path("currentMeasurementId").asText());}
 private Map<String,Object> quoted(long v){return Map.of("version",v,"origin","PROVIDER_QUOTED","quotedAmount",12500,"currency","XOF","source","FORWARDER","note","PRIVATE-NOTE");}
 @Test void previewFinalAndSafeTrackingPreserveCommercialValues()throws Exception{
 var before=jdbc.queryForMap("select status,fulfillment_status,estimated_shipping_amount,initial_amount_due from orders where id=?",orderId());
 auth(get(base()+"/shipping-assessments")).andExpect(status().isOk()).andExpect(jsonPath("$[0].assessment.status").value("PENDING"));
 assertThat(jdbc.queryForObject("select count(*) from shipping_assessments",Integer.class)).isZero();
 var m=measure(0,Map.of("chargeableWeightKg",2.8,"source","FORWARDER","note","PRIVATE-NOTE"));
 assertThat(m.path("measurements").get(0).path("actorId").asText()).isEqualTo(actor.toString());
 auth(post(shipping()+"/shipping-preview"),calculated(1,m)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(9800));
 assertThat(jdbc.queryForObject("select count(*) from shipping_calculations",Integer.class)).isZero();
 auth(post(shipping()+"/shipping-finalizations"),calculated(1,m)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINALIZED"));
 var t=read(track().andExpect(status().isOk()).andExpect(jsonPath("$.shippingFinal.amount").value(9800)).andReturn());
 assertThat(t.toString()).doesNotContain("PRIVATE-NOTE","actorId","assessmentId","organizationId","correctionReason");
 assertThat(jdbc.queryForMap("select status,fulfillment_status,estimated_shipping_amount,initial_amount_due from orders where id=?",orderId())).isEqualTo(before);
 assertThat(jdbc.queryForObject("select count(*) from payment_requests where purpose='SHIPPING_BALANCE'",Integer.class)).isEqualTo(1);
 }
 @Test void appendOnlyCorrections()throws Exception{
 var m=measure(0,Map.of("actualWeightKg",2.8,"source","FORWARDER"));
 var first=read(auth(post(shipping()+"/shipping-finalizations"),calculated(1,m)).andExpect(status().isOk()).andReturn());
 auth(post(shipping()+"/shipping-finalizations"),quoted(2)).andExpect(status().isBadRequest());
 auth(post(shipping()+"/shipping-measurements"),Map.of("version",2,"information",Map.of("actualWeightKg",2.6))).andExpect(status().isConflict());
 var corrected=measure(2,Map.of("actualWeightKg",2.6,"supersedesMeasurementId",m.path("currentMeasurementId").asText(),"correctionReason","Mesure corrigée","source","DOCUMENT"));
 var body=new HashMap<String,Object>(calculated(3,corrected));body.put("correctionReason","Correction documentée");
 var result=read(auth(post(shipping()+"/shipping-finalizations"),body).andExpect(status().isOk()).andReturn());
 assertThat(result.path("calculations").size()).isEqualTo(2);assertThat(result.path("calculations").get(0)).isEqualTo(first.path("calculations").get(0));
 assertThat(result.path("calculations").get(1).path("supersedesCalculationId").asText()).isEqualTo(first.path("currentFinalCalculationId").asText());
 assertThat(result.path("calculations").get(1).path("explanation").path("amount").asInt()).isEqualTo(9100);
 assertThat(result.path("measurements").size()).isEqualTo(2);
 for(String table:List.of("shipping_calculations","shipping_measurements","shipping_assessments"))assertThatThrownBy(()->jdbc.update("delete from "+table)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 assertThatThrownBy(()->jdbc.update("update shipping_calculations set note='rewrite'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 assertThatThrownBy(()->jdbc.update("update shipping_measurements set actor_id=?",UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test void quoteIsPureUntilFinalized()throws Exception{
 auth(post(shipping()+"/shipping-preview"),quoted(0)).andExpect(status().isOk()).andExpect(jsonPath("$.billableQuantity").doesNotExist());
 assertThat(jdbc.queryForObject("select count(*) from shipping_assessments",Integer.class)).isZero();
 auth(post(shipping()+"/shipping-finalizations"),quoted(0)).andExpect(status().isOk());
 track().andExpect(jsonPath("$.shippingFinal.origin").value("PROVIDER_QUOTED")).andExpect(jsonPath("$.shippingFinal.amount").value(12500)).andExpect(jsonPath("$.shippingFinal.rate").doesNotExist());
 assertThat(jdbc.queryForObject("select count(*) from shipping_measurements",Integer.class)).isZero();
 auth(post(shipping()+"/shipping-finalizations"),quoted(0)).andExpect(status().isConflict());
 }
 @Test void concurrentFinalizations()throws Exception{
 var start=new java.util.concurrent.CountDownLatch(1);
 try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
 java.util.concurrent.Callable<Integer> call=()->{start.await();return auth(post(shipping()+"/shipping-finalizations"),quoted(0)).andReturn().getResponse().getStatus();};
 var a=pool.submit(call);var b=pool.submit(call);start.countDown();
 assertThat(List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);}
 assertThat(jdbc.queryForObject("select count(*) from shipping_calculations",Integer.class)).isEqualTo(1);
 }
 @Test void staleAndInvalidFinalRollback()throws Exception{
 auth(post(shipping()+"/shipping-finalizations"),Map.of("version",0,"origin","CALCULATED")).andExpect(status().isConflict());
 assertThat(jdbc.queryForObject("select count(*) from shipping_assessments",Integer.class)).isZero();
 var old=measure(0,Map.of("actualWeightKg",2.8));measure(1,Map.of("actualWeightKg",2.6,"supersedesMeasurementId",old.path("currentMeasurementId").asText(),"correctionReason","Correction"));
 auth(post(shipping()+"/shipping-preview"),calculated(2,old)).andExpect(status().isConflict());
 }
 @Test void authorizationScopeAndActor()throws Exception{
 mvc.perform(get(base()+"/shipping-assessments")).andExpect(status().isUnauthorized());
 var fake=new HashMap<String,Object>(quoted(0));fake.put("actorId",UUID.randomUUID());
 auth(post(shipping()+"/shipping-finalizations"),fake).andExpect(status().isBadRequest());
 UUID other=UUID.fromString(read(auth(post("/api/organizations"),Map.of("name","Autre")).andReturn()).path("id").asText());
 auth(get("/api/organizations/"+other+"/grouping-campaigns/"+campaignId()+"/shipping-assessments")).andExpect(status().isNotFound());
 var c=createCampaign(org,variant);var number=order(c,c.path("offers").get(0).path("id").asText(),1,5000,0,"CONFIRMED");
 var id=jdbc.queryForObject("select id from orders where order_number=?",UUID.class,number);
 auth(post(base()+"/orders/"+id+"/shipping-finalizations"),quoted(0)).andExpect(status().isNotFound());
 jdbc.update("update organization_members set role='VIEWER' where organization_id=?",org);
 auth(get(base()+"/shipping-assessments")).andExpect(status().isOk());auth(post(shipping()+"/shipping-finalizations"),quoted(0)).andExpect(status().isForbidden());
 auth(post(shipping()+"/shipping-preview"),quoted(0)).andExpect(status().isForbidden());
 }
 @Test void onlyConfirmedAndUnknownNotZero()throws Exception{
 auth(get(base()+"/shipping-assessments")).andExpect(jsonPath("$[0].assessment.snapshot.estimatedWeightKg").doesNotExist()).andExpect(jsonPath("$[0].assessment.snapshot.totalItems").value(1));
 jdbc.update("update orders set status='CANCELLED',cancelled_at=current_timestamp where id=?",orderId());
 auth(get(base()+"/shipping-assessments")).andExpect(jsonPath("$.length()").value(0));
 auth(post(shipping()+"/shipping-finalizations"),quoted(0)).andExpect(status().isConflict());
 }

 @Test void volumetricTermsFreezeAtOpenWithoutChangingHistoricalOrders()throws Exception{
 var terms=new HashMap<String,Object>(Map.of("transportMode","AIR","shippingPricingMethod","PER_KG","shippingCollectionMode","AFTER_FINAL_ASSESSMENT","shippingRate",3500,"volumetricDivisor",7000));
 String path="/api/organizations/"+org+"/grouping-campaigns";
 var c=read(auth(post(path),Map.of("title","Volumétrique","shippingTerms",terms)).andExpect(status().isCreated()).andReturn());
 path+="/"+c.path("id").asText();
 c=read(auth(post(path+"/offers"),Map.of("campaignVersion",c.path("version").asLong(),"productVariantId",variant,"unitPrice",5000)).andExpect(status().isOk()).andReturn());
 c=read(auth(post(path+"/open"),Map.of("version",c.path("version").asLong())).andExpect(status().isOk()).andReturn());
 terms.put("volumetricDivisor",6000);
 auth(put(path),Map.of("version",c.path("version").asLong(),"title","Volumétrique","shippingTerms",terms)).andExpect(status().isConflict());
 assertThat(jdbc.queryForObject("select volumetric_divisor from grouping_campaigns where id=?",Integer.class,UUID.fromString(c.path("id").asText()))).isEqualTo(7000);
 assertThat(jdbc.queryForObject("select shipping_terms_snapshot->>'volumetricDivisor' from orders where id=?",String.class,orderId())).isNull();
 }
 @Test void databaseRejectsCrossAssessmentCurrentFinalAndSnapshotRewrite()throws Exception{
 auth(post(shipping()+"/shipping-finalizations"),quoted(0)).andExpect(status().isOk());
 assertThatThrownBy(()->jdbc.update("update shipping_assessments set snapshot=jsonb_set(snapshot,'{totalItems}','999'),version=version+1")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 assertThatThrownBy(()->jdbc.update("update shipping_assessments set current_final_calculation_id=?,version=version+1",UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
        var c=read(auth(post(base),Map.of("title","Groupage","shippingTerms",Map.of("transportMode","AIR","shippingPricingMethod","PER_KG","shippingCollectionMode","AFTER_FINAL_ASSESSMENT","shippingRate",3500))).andExpect(status().isCreated()).andReturn());
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

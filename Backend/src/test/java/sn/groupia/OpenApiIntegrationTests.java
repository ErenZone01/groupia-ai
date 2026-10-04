package sn.groupia;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)
class OpenApiIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    private JsonNode spec() throws Exception {
        var body=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
    @Test void specificationCoversEveryBusinessRestOperationAndResolvesSchemas() throws Exception {
        var doc=spec();assertThat(doc.path("openapi").asText()).startsWith("3.");
        var expected=new TreeSet<String>();
        mappings.getHandlerMethods().forEach((mapping,handler)->{
            if(handler.getBeanType().getPackageName().startsWith("sn.groupia.")) {
                for(var path:mapping.getPatternValues())for(var method:mapping.getMethodsCondition().getMethods()){
                    expected.add(method.name().toLowerCase()+" "+path);
                }
            }
        });
        var actual=new TreeSet<String>();
        doc.path("paths").properties().forEach(p->p.getValue().properties().forEach(op->actual.add(op.getKey()+" "+p.getKey())));
        assertThat(actual).isEqualTo(expected);
        assertReferences(doc,doc);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/groupia-openapi.json"),doc.toPrettyString());
        assertThat(doc.at("/components/schemas/OrderReceipt/properties/orderNumber").isMissingNode()).isFalse();
        assertThat(doc.at("/components/schemas/OrderReplay/properties/replayed").isMissingNode()).isFalse();
        assertThat(doc.at("/components/schemas/PaymentRequestView/properties/expiresAt/nullable").asBoolean()).isTrue();
    }
    private void assertReferences(JsonNode root,JsonNode node) {
        if(node.isObject() && node.has("$ref") && node.get("$ref").asText().startsWith("#/")) {
            assertThat(root.at(node.get("$ref").asText().substring(1)).isMissingNode()).as(node.get("$ref").asText()).isFalse();
        }
        if(node.isObject() || node.isArray())for(var child:node)assertReferences(root,child);
    }
    @Test void schemesAreScopedToTheCorrectOperations() throws Exception {
        var paths=spec().path("paths");
        assertThat(paths.path("/api/organizations").path("get").path("security").get(0).has("BearerJWT")).isTrue();
        assertThat(paths.path("/api/public/orders/track").path("get").path("security").get(0).has("OrderTrackingToken")).isTrue();
        assertThat(paths.path("/api/payment/webhooks/{provider}").path("post").path("security").get(0).has("ProviderWebhookSecret")).isTrue();
        assertThat(paths.path("/api/auth/login").path("post").path("security").size()).isZero();
        assertThat(paths.path("/api/public/groupings/{publicIdentifier}").path("get").path("security").size()).isZero();
        paths.properties().forEach(p->p.getValue().properties().forEach(op->{
            if(!p.getKey().contains("/webhooks/"))assertThat(op.getValue().path("security").toString()).doesNotContain("ProviderWebhookSecret");
        }));
    }
    @Test void supplierSummaryIsAuthenticatedAndContainsOnlySupplierSnapshots() throws Exception {
        var doc=spec();
        var op=doc.path("paths").path("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}/supplier-summary").path("get");
        assertThat(op.path("operationId").asText()).isEqualTo("supplierSummary");
        assertThat(op.path("security").get(0).has("BearerJWT")).isTrue();
        assertThat(op.path("responses").path("200").toString()).contains("SupplierSummaryView");
        assertThat(doc.at("/components/schemas/SupplierSummaryLine/properties").propertyNames())
            .containsExactlyInAnyOrder("productNameSnapshot","productDescriptionSnapshot","commercialLabelSnapshot","variantAttributesSnapshot","skuSnapshot","saleUnitSnapshot","totalQuantity","goodsAmount");
    }
    @Test void supplierPurchaseDocumentsCreationReplayAndImmutableRead() throws Exception {
        var doc=spec();
        var path=doc.path("paths").path("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}/supplier-order");
        assertThat(path.path("post").path("operationId").asText()).isEqualTo("createSupplierOrder");
        assertThat(path.path("get").path("operationId").asText()).isEqualTo("readSupplierOrder");
        assertThat(path.path("post").path("responses").has("201")).isTrue();
        assertThat(path.path("post").path("responses").has("200")).isTrue();
        assertThat(doc.at("/components/schemas/CreateSupplierOrder/required").toString()).contains("campaignVersion");
        assertThat(doc.at("/components/schemas/SupplierOrderView/properties").propertyNames()).doesNotContain("acceptedOrderIds","orderedBy");
    }
    @Test void logisticsContractSeparatesPrivateShipmentAndSafeTracking() throws Exception {
        var doc=spec();
        String base="/api/organizations/{organizationId}/grouping-campaigns/{campaignId}";
        for(String action:List.of("ship","arrive","shipment")) {
            var operation=doc.path("paths").path(base+"/"+action).path(action.equals("shipment")?"get":"post");
            assertThat(operation.path("security").get(0).has("BearerJWT")).isTrue();
            assertThat(operation.path("responses").path("200").toString()).contains("CampaignShipmentView");
        }
        assertThat(doc.at("/components/schemas/GroupingProgress/properties").propertyNames())
            .containsExactlyInAnyOrder("status","departedAt","expectedArrivalAt","arrivedAt");
        assertThat(doc.at("/components/schemas/OrderReceipt/properties").propertyNames()).doesNotContain("groupingProgress");
        assertThat(doc.at("/components/schemas/OrderTrackingView/properties").propertyNames()).contains("groupingProgress").doesNotContain("trackingToken");
    }
    @Test void checkoutDocumentsBothResponsesAndRequiredIdempotency() throws Exception {
        var op=spec().path("paths").path("/api/public/groupings/{publicIdentifier}/checkout").path("post");
        assertThat(op.path("responses").has("201")).isTrue();assertThat(op.path("responses").has("200")).isTrue();
        assertThat(op.path("responses").has("409")).isTrue();
        boolean required=false;
        for(var p:op.path("parameters"))if(p.path("name").asText().equals("Idempotency-Key"))required=p.path("required").asBoolean();
        assertThat(required).isTrue();
        assertThat(op.path("responses").path("409").toString()).contains("application/problem+json","GroupiaProblem");
    }
    @Test void documentationDoesNotOpenProtectedEndpoints() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        mvc.perform(get("/api/organizations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/orders/track")).andExpect(status().isNotFound());
    }
    @Test void collectionContainsOnlyExistingRoutesAndNoProviderRequests() throws Exception {
        var doc=spec();
        var collection=json.readTree(Files.readString(Path.of("postman/Groupia-local.postman_collection.json")));
        assertThat(collection.path("item").size()).isEqualTo(9);
        for(var folder:collection.path("item"))for(var item:folder.path("item")) {
            var request=item.path("request");
            String url=request.path("url").asText();
            assertThat(url).startsWith("{{baseUrl}}/");
            String path=url.substring("{{baseUrl}}".length()).split("\\?")[0];
            if(path.equals("/actuator/health") || path.equals("/v3/api-docs"))continue;
            String method=request.path("method").asText().toLowerCase();
            boolean found=false;
            for(var entry:doc.path("paths").properties()) {
                String pattern=entry.getKey().replaceAll("\\{[^}]+\\}","[^/]+");
                if(path.matches(pattern) && entry.getValue().has(method))found=true;
            }
            assertThat(found).as(item.path("name").asText()).isTrue();
            assertThat(url).doesNotContain("/api/payment/webhooks");
        }
        var env=json.readTree(Files.readString(Path.of("postman/Groupia-local.postman_environment.json")));
        Set<String> defaults=Set.of("baseUrl","runPhase","newScenario","firstName","lastName");
        for(var value:env.path("values")){
            if(!defaults.contains(value.path("key").asText()))assertThat(value.path("value").asText()).isEmpty();
            assertThat(value.path("key").asText()).doesNotContain("BICTORYS","apiKey","webhookSecret");
        }
    }
}

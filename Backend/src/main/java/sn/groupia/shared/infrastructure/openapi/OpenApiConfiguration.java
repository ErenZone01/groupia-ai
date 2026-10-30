package sn.groupia.shared.infrastructure.openapi;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;
import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

/** Technical HTTP contracts, without dependencies on business module internals. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    private Schema<?> problemSchema() {
        return new ObjectSchema()
                .addProperty("type", new StringSchema())
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema())
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema())
                .addProperty(
                        "code", new StringSchema().description("Stable application error code"))
                .addProperty(
                        "errors",
                        new ArraySchema()
                                .items(
                                        new ObjectSchema()
                                                .addProperty("field", new StringSchema())
                                                .addProperty("message", new StringSchema())))
                .addProperty("details", new ObjectSchema().additionalProperties(true));
    }

    @Bean
    OpenAPI groupiaOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Groupia API")
                                .version("V1")
                                .description(
                                        "Organization actions require JWT, active membership and explicit permission. Order access requires a private tracking token."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "CustomerSession",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name("__Host-groupia_customer")
                                                .description(
                                                        "Opaque HttpOnly cookie; groupia_customer in explicit local profile."))
                                .addSecuritySchemes(
                                        "CustomerCsrf",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-XSRF-TOKEN")
                                                .description(
                                                        "Fetch /api/public/customer-access/csrf first; retain its cookie and send the returned token."))
                                .addSecuritySchemes(
                                        "BearerJWT",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT"))
                                .addSecuritySchemes(
                                        "OrderTrackingToken",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-Order-Tracking-Token")
                                                .description(
                                                        "Private token returned once at checkout. Never place it in a URL."))
                                .addSecuritySchemes(
                                        "ProviderWebhookSecret",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-Secret-Key")
                                                .description(
                                                        "Bictorys server webhook only. Never put provider credentials in Postman.")));
    }

    @Bean
    OpenApiCustomizer groupiaHttpContracts() {
        return api -> {
            api.getComponents().addSchemas("GroupiaProblem", problemSchema());
            api.getPaths()
                    .forEach(
                            (path, item) ->
                                    item.readOperationsMap()
                                            .forEach(
                                                    (method, operation) -> {
                                                        boolean organizer =
                                                                path.startsWith(
                                                                        "/api/organizations");
                                                        boolean privateProfile =
                                                                path.equals("/api/auth/me");
                                                        boolean tracking =
                                                                path.startsWith(
                                                                        "/api/public/orders");
                                                        boolean webhook =
                                                                path.startsWith(
                                                                        "/api/payment/webhooks/");
                                                        operation.setSecurity(
                                                                (organizer || privateProfile)
                                                                        ? List.of(
                                                                                new SecurityRequirement()
                                                                                        .addList(
                                                                                                "BearerJWT"))
                                                                        : tracking
                                                                                ? List.of(
                                                                                        new SecurityRequirement()
                                                                                                .addList(
                                                                                                        "OrderTrackingToken"))
                                                                                : webhook
                                                                                        ? List.of(
                                                                                                new SecurityRequirement()
                                                                                                        .addList(
                                                                                                                "ProviderWebhookSecret"))
                                                                                        : List
                                                                                                .of());
                                                        if (path.startsWith(
                                                                "/api/public/customer-access/")) {
                                                            var security =
                                                                    new SecurityRequirement();
                                                            if (path.endsWith("/session")
                                                                    || path.contains("/orders")
                                                                    || path.endsWith("/logout"))
                                                                security.addList("CustomerSession");
                                                            if (method == PathItem.HttpMethod.POST)
                                                                security.addList("CustomerCsrf");
                                                            operation.setSecurity(
                                                                    security.isEmpty()
                                                                            ? List.of()
                                                                            : List.of(security));
                                                        }
                                                        if (operation.getParameters() != null) {
                                                            operation
                                                                    .getParameters()
                                                                    .removeIf(
                                                                            p ->
                                                                                    "header"
                                                                                                    .equals(
                                                                                                            p
                                                                                                                    .getIn())
                                                                                            && "X-Order-Tracking-Token"
                                                                                                    .equals(
                                                                                                            p
                                                                                                                    .getName()));
                                                            for (var p :
                                                                    operation.getParameters()) {
                                                                if ("Idempotency-Key"
                                                                        .equals(p.getName())) {
                                                                    p.setRequired(true);
                                                                    p.setDescription(
                                                                            "Unique logical-operation key; reuse unchanged on retry. Changed request content with the same key returns 409.");
                                                                    p.setSchema(
                                                                            new StringSchema()
                                                                                    .minLength(16)
                                                                                    .maxLength(
                                                                                            128));
                                                                }
                                                            }
                                                        }
                                                        var responses = operation.getResponses();
                                                        Map<String, String> errors =
                                                                new LinkedHashMap<>();
                                                        errors.put(
                                                                "400",
                                                                "Invalid JSON, fields or business input");
                                                        errors.put(
                                                                "404",
                                                                "Resource or private tracking access unavailable");
                                                        errors.put(
                                                                "409",
                                                                "Version, idempotency or business state conflict");
                                                        errors.put(
                                                                "500",
                                                                "Internal failure without sensitive details");
                                                        if (organizer
                                                                || privateProfile
                                                                || webhook
                                                                || path.endsWith("/login")) {
                                                            errors.put(
                                                                    "401", "Authentication failed");
                                                        }
                                                        if (organizer) {
                                                            errors.put(
                                                                    "403",
                                                                    "Organization membership or permission denied");
                                                        }
                                                        if (path.startsWith(
                                                                "/api/public/customer-access/")) {
                                                            errors.put(
                                                                    "401",
                                                                    "Customer session or link unavailable");
                                                            errors.put(
                                                                    "403",
                                                                    "Phone verification or CSRF required");
                                                            errors.put("429", "Retry later");
                                                            errors.put(
                                                                    "503",
                                                                    "Customer access disabled");
                                                        }
                                                        if (tracking) {
                                                            errors.put(
                                                                    "429", "Tracking rate limit");
                                                        }
                                                        if (path.startsWith("/api/auth/")) {
                                                            errors.put(
                                                                    "429",
                                                                    "Authentication attempt quota exceeded; Retry-After header indicates when to retry");
                                                        }
                                                        if (path.endsWith("/payments") || webhook) {
                                                            errors.put(
                                                                    "503",
                                                                    "Payment provider unavailable");
                                                        }
                                                        if (webhook) {
                                                            errors.put(
                                                                    "413",
                                                                    "Webhook exceeds 64 KiB");
                                                        }
                                                        errors.forEach(
                                                                (code, description) ->
                                                                        responses.addApiResponse(
                                                                                code,
                                                                                new ApiResponse()
                                                                                        .description(
                                                                                                description)
                                                                                        .content(
                                                                                                new Content()
                                                                                                        .addMediaType(
                                                                                                                "application/problem+json",
                                                                                                                new MediaType()
                                                                                                                        .schema(
                                                                                                                                new Schema<>()
                                                                                                                                        .$ref(
                                                                                                                                                "#/components/schemas/GroupiaProblem"))))));
                                                        if (method == PathItem.HttpMethod.POST
                                                                && Set.of(
                                                                                "/api/organizations",
                                                                                "/api/organizations/{organizationId}/products",
                                                                                "/api/organizations/{organizationId}/grouping-campaigns")
                                                                        .contains(path)) {
                                                            var success = responses.remove("200");
                                                            if (success != null) {
                                                                responses.addApiResponse(
                                                                        "201",
                                                                        success.description(
                                                                                "Created"));
                                                            }
                                                        }
                                                        if (webhook) {
                                                            responses.remove("200");
                                                            responses.addApiResponse(
                                                                    "202",
                                                                    new ApiResponse()
                                                                            .description(
                                                                                    "Receipt retained; not proof of financial success"));
                                                        }
                                                    }));
        };
    }
}

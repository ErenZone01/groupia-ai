package sn.groupia.shared.infrastructure.openapi;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;

/** Technical HTTP contracts, without dependencies on business module internals. */
@Configuration(proxyBeanMethods=false)
class OpenApiConfiguration {
    private Schema<?> problemSchema() {
        return new ObjectSchema().addProperty("type",new StringSchema())
                .addProperty("title",new StringSchema()).addProperty("status",new IntegerSchema())
                .addProperty("detail",new StringSchema()).addProperty("instance",new StringSchema())
                .addProperty("code",new StringSchema().description("Stable application error code"))
                .addProperty("errors",new ArraySchema().items(new ObjectSchema()
                        .addProperty("field",new StringSchema()).addProperty("message",new StringSchema())))
                .addProperty("details",new ObjectSchema().additionalProperties(true));
    }
    @Bean OpenAPI groupiaOpenApi() {
        return new OpenAPI().info(new Info().title("Groupia API").version("V1")
                .description("Organization actions require JWT, active membership and explicit permission. Order access requires a private tracking token."))
                .components(new Components()
                    .addSecuritySchemes("BearerJWT",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                    .addSecuritySchemes("OrderTrackingToken",new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                            .name("X-Order-Tracking-Token").description("Private token returned once at checkout. Never place it in a URL."))
                    .addSecuritySchemes("ProviderWebhookSecret",new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                            .name("X-Secret-Key").description("Bictorys server webhook only. Never put provider credentials in Postman.")));
    }
    @Bean OpenApiCustomizer groupiaHttpContracts() {
        return api -> {
            api.getComponents().addSchemas("GroupiaProblem",problemSchema());
            api.getPaths().forEach((path,item)->item.readOperationsMap().forEach((method,operation)->{
            boolean organizer=path.startsWith("/api/organizations");
            boolean tracking=path.startsWith("/api/public/orders");
            boolean webhook=path.startsWith("/api/payment/webhooks/");
            operation.setSecurity(organizer?List.of(new SecurityRequirement().addList("BearerJWT")):
                    tracking?List.of(new SecurityRequirement().addList("OrderTrackingToken")):
                    webhook?List.of(new SecurityRequirement().addList("ProviderWebhookSecret")):List.of());
            if(operation.getParameters()!=null) {
                operation.getParameters().removeIf(p->"header".equals(p.getIn()) && "X-Order-Tracking-Token".equals(p.getName()));
                for(var p:operation.getParameters()) {
                    if("Idempotency-Key".equals(p.getName())) {
                        p.setRequired(true);p.setDescription("Unique logical-operation key; reuse unchanged on retry. Changed checkout content with the same key returns 409.");
                        p.setSchema(new StringSchema().minLength(16).maxLength(128));
                    }
                }
            }
            var responses=operation.getResponses();
            Map<String,String> errors=new LinkedHashMap<>();
            errors.put("400","Invalid JSON, fields or business input");
            errors.put("404","Resource or private tracking access unavailable");
            errors.put("409","Version, idempotency or business state conflict");
            errors.put("500","Internal failure without sensitive details");
            if(organizer || webhook || path.endsWith("/login")){errors.put("401","Authentication failed");}
            if(organizer){errors.put("403","Organization membership or permission denied");}
            if(tracking){errors.put("429","Tracking rate limit");}
            if(path.endsWith("/payments") || webhook){errors.put("503","Payment provider unavailable");}
            if(webhook){errors.put("413","Webhook exceeds 64 KiB");}
            errors.forEach((code,description)->responses.addApiResponse(code,new ApiResponse().description(description)
                    .content(new Content().addMediaType("application/problem+json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/GroupiaProblem"))))));
            if(method==PathItem.HttpMethod.POST && Set.of("/api/auth/register","/api/organizations",
                    "/api/organizations/{organizationId}/products","/api/organizations/{organizationId}/grouping-campaigns").contains(path)) {
                var success=responses.remove("200");
                if(success!=null){responses.addApiResponse("201",success.description("Created"));}
            }
            if(webhook){responses.remove("200");responses.addApiResponse("202",new ApiResponse().description("Receipt retained; not proof of financial success"));}
            }));
        };
    }
}

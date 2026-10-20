package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import sn.groupia.media.application.service.ProductMediaService;
import sn.groupia.media.domain.repository.MediaRecords;
import sn.groupia.media.domain.service.*;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "groupia.media.enabled=true",
            "groupia.media.storage-type=filesystem",
            "groupia.media.local-root=target/test-media",
            "groupia.media.cleanup-delay=3600000"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductMediaIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    private static String fixtureHash;
    @Autowired ProductMediaService service;
    @MockitoSpyBean MediaObjectStorage storage;
    @MockitoSpyBean MediaRecords records;
    @MockitoSpyBean sn.groupia.media.infrastructure.image.ImageNormalizer images;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    UUID org, product, user;
    String bearer;
    byte[] bytes;

    @BeforeEach
    void setup() throws Exception {
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute("truncate products,organizations,user_accounts,event_publication cascade");
        reset(storage, records, images);
        user = UUID.randomUUID();
        if (fixtureHash == null) fixtureHash = passwords.encode(UUID.randomUUID().toString());
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values(?,?,?,'ACTIVE',now(),now())",
                user,
                "+221771234567",
                fixtureHash);
        bearer =
                "Bearer "
                        + encoder.encode(
                                        JwtEncoderParameters.from(
                                                JwsHeader.with(MacAlgorithm.HS256).build(),
                                                JwtClaimsSet.builder()
                                                        .issuer("https://groupia.test")
                                                        .audience(List.of("groupia-api-test"))
                                                        .subject(user.toString())
                                                        .claim("sid", UUID.randomUUID().toString())
                                                        .issuedAt(Instant.now())
                                                        .expiresAt(Instant.now().plusSeconds(600))
                                                        .build()))
                                .getTokenValue();
        org =
                UUID.fromString(
                        body(mvc.perform(
                                                post("/api/organizations")
                                                        .header("Authorization", bearer)
                                                        .contentType("application/json")
                                                        .content("{\"name\":\"Media tests\"}"))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .path("id")
                                .asText());
        product =
                UUID.fromString(
                        body(mvc.perform(
                                                post("/api/organizations/" + org + "/products")
                                                        .header("Authorization", bearer)
                                                        .contentType("application/json")
                                                        .content("{\"name\":\"Produit photo\"}"))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .path("id")
                                .asText());
        bytes = MediaImageTests.image("png", 12, 8, false);
    }

    private String base() {
        return "/api/organizations/" + org + "/products/" + product + "/media";
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions upload(UUID key, byte[] value) throws Exception {
        return mvc.perform(
                multipart(base())
                        .file(
                                new MockMultipartFile(
                                        "file", "../../private-name.png", "image/png", value))
                        .header("Authorization", bearer)
                        .header("Idempotency-Key", key));
    }

    private JsonNode add() throws Exception {
        return body(upload(UUID.randomUUID(), bytes).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode gallery() throws Exception {
        return body(
                mvc.perform(get(base()).header("Authorization", bearer))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    @Test
    void emptyReadDoesNotCreateGallery() throws Exception {
        assertThat(gallery().path("version").asLong()).isZero();
        assertThat(gallery().path("items").size()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from product_media_galleries", Integer.class))
                .isZero();
    }

    @Test
    void realMultipartNormalizesAndPublishesOnlyReadyAssets() throws Exception {
        var media = add();
        assertThat(media.path("state").asText()).isEqualTo("READY");
        assertThat(media.path("position").asInt()).isZero();
        var key =
                jdbc.queryForObject(
                        "select display_storage_key from product_media where id=?",
                        String.class,
                        UUID.fromString(media.path("id").asText()));
        assertThat(key).doesNotContain("private-name", "..");
        mvc.perform(get(media.path("displayUrl").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
    }

    @Test
    void limitsToFiveIncludingReservations() throws Exception {
        for (int i = 0; i < 5; i++) add();
        upload(UUID.randomUUID(), bytes)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_LIMIT_REACHED"));
        assertThat(gallery().path("items").size()).isEqualTo(5);
        assertThatThrownBy(
                        () -> jdbc.update("update product_media set position=5 where position=4"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void duplicateAndLostResponseRetryDoNotDuplicateMedia() throws Exception {
        UUID key = UUID.randomUUID();
        var first = body(upload(key, bytes).andExpect(status().isCreated()).andReturn());
        var second = body(upload(key, bytes).andExpect(status().isCreated()).andReturn());
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(gallery().path("items").size()).isEqualTo(1);
        upload(key, MediaImageTests.image("png", 4, 4, false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void swapsThenDeletesPrincipalAndCompacts() throws Exception {
        var a = add();
        var b = add();
        long version = gallery().path("version").asLong();
        mvc.perform(
                        put(base() + "/order")
                                .header("Authorization", bearer)
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        version,
                                                        "mediaIds",
                                                        List.of(
                                                                b.path("id").asText(),
                                                                a.path("id").asText())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(b.path("id").asText()));
        mvc.perform(
                        put(base() + "/order")
                                .header("Authorization", bearer)
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        version,
                                                        "mediaIds",
                                                        List.of(
                                                                a.path("id").asText(),
                                                                b.path("id").asText())))))
                .andExpect(status().isConflict());
        mvc.perform(delete(base() + "/" + b.path("id").asText()).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(delete(base() + "/" + b.path("id").asText()).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        assertThat(gallery().path("items").get(0).path("id").asText())
                .isEqualTo(a.path("id").asText());
        assertThat(gallery().path("items").get(0).path("position").asInt()).isZero();
        mvc.perform(get(b.path("displayUrl").asText())).andExpect(status().isNotFound());
    }

    @Test
    void archivedProductAllowsRemovalButNotUploadOrReorder() throws Exception {
        var item = add();
        jdbc.update("update products set status='ARCHIVED' where id=?", product);
        upload(UUID.randomUUID(), bytes).andExpect(status().isConflict());
        mvc.perform(delete(base() + "/" + item.path("id").asText()).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        assertThat(gallery().path("items").size()).isZero();
    }

    @Test
    void viewPermissionCannotMutate() throws Exception {
        jdbc.update(
                "update organization_members set role='VIEWER' where organization_id=? and user_account_id=?",
                org,
                user);
        assertThat(gallery().path("canManage").asBoolean()).isFalse();
        upload(UUID.randomUUID(), bytes).andExpect(status().isForbidden());
    }

    @Test
    void rejectsForeignProductAndBatchIds() throws Exception {
        mvc.perform(
                        get("/api/organizations/"
                                        + org
                                        + "/products/"
                                        + UUID.randomUUID()
                                        + "/media")
                                .header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/organizations/" + org + "/product-media")
                                .param("productIds", product + "," + UUID.randomUUID())
                                .header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/organizations/" + org + "/product-media")
                                .param("productIds", product + "," + product)
                                .header("Authorization", bearer))
                .andExpect(status().isBadRequest());
    }

    @Test
    void existingMediaCannotBeReadOrDeletedThroughAnotherProductOrOrganization() throws Exception {
        var media = add();
        String otherProduct =
                body(mvc.perform(
                                        post("/api/organizations/" + org + "/products")
                                                .header("Authorization", bearer)
                                                .contentType("application/json")
                                                .content("{\"name\":\"Autre produit\"}"))
                                .andExpect(status().isCreated())
                                .andReturn())
                        .path("id")
                        .asText();
        String other =
                "/api/organizations/"
                        + org
                        + "/products/"
                        + otherProduct
                        + "/media/"
                        + media.path("id").asText();
        mvc.perform(get(other).header("Authorization", bearer)).andExpect(status().isNotFound());
        mvc.perform(delete(other).header("Authorization", bearer)).andExpect(status().isNotFound());
        UUID otherOrg =
                UUID.fromString(
                        body(mvc.perform(
                                                post("/api/organizations")
                                                        .header("Authorization", bearer)
                                                        .contentType("application/json")
                                                        .content("{\"name\":\"Autre boutique\"}"))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .path("id")
                                .asText());
        mvc.perform(
                        get("/api/organizations/" + otherOrg + "/products/" + product + "/media")
                                .header("Authorization", bearer))
                .andExpect(status().isNotFound());
        assertThat(gallery().path("items").size()).isEqualTo(1);
    }

    @Test
    void concurrentReordersHaveOnlyOneWinner() throws Exception {
        var a = add();
        var b = add();
        long version = gallery().path("version").asLong();
        String body =
                json.writeValueAsString(
                        Map.of(
                                "version",
                                version,
                                "mediaIds",
                                List.of(b.path("id").asText(), a.path("id").asText())));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<Integer> call =
                    () -> {
                        barrier.await();
                        return mvc.perform(
                                        put(base() + "/order")
                                                .header("Authorization", bearer)
                                                .contentType("application/json")
                                                .content(body))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(gallery().path("items").get(0).path("id").asText())
                .isEqualTo(b.path("id").asText());
    }

    @Test
    void multipartByteLimitReturns413WithoutReservation() throws Exception {
        mvc.perform(
                        multipart(base())
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "oversize.png",
                                                "image/png",
                                                new byte[8388609]))
                                .header("Authorization", bearer)
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isPayloadTooLarge());
        assertThat(jdbc.queryForObject("select count(*) from product_media", Integer.class))
                .isZero();
    }

    @Test
    void batchReturnsPrimaryAndCount() throws Exception {
        add();
        add();
        mvc.perform(
                        get("/api/organizations/" + org + "/product-media")
                                .param("productIds", product.toString())
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].imageCount").value(2))
                .andExpect(jsonPath("$[0].primaryThumbnailUrl").isString());
    }

    @Test
    void partialStorageFailureIsNeverPublishedAndCleanupRecovers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        inv -> {
                            if (calls.incrementAndGet() == 2) throw new MediaStorageException();
                            return inv.callRealMethod();
                        })
                .when(storage)
                .put(any(), any());
        var result =
                body(upload(UUID.randomUUID(), bytes).andExpect(status().isAccepted()).andReturn());
        assertThat(result.path("state").asText()).isEqualTo("UPLOADING");
        assertThat(result.path("displayUrl").isNull()).isTrue();
        assertThat(gallery().path("items").size()).isZero();
        jdbc.update(
                "update product_media set upload_expires_at=now()-interval '1 hour',next_cleanup_at=now()-interval '1 hour'");
        service.cleanup();
        assertThat(jdbc.queryForObject("select state from product_media", String.class))
                .isEqualTo("DELETED");
    }

    @Test
    void headRecoversTimeoutAfterSuccessfulWrite() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        inv -> {
                            inv.callRealMethod();
                            if (calls.incrementAndGet() == 2) throw new MediaStorageException();
                            return null;
                        })
                .when(storage)
                .put(any(), any());
        upload(UUID.randomUUID(), bytes).andExpect(status().isCreated());
        verify(storage, atLeastOnce()).head(anyString());
    }

    @Test
    void failedFinalizationDoesNotBlindlyDeleteObjects() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic failure"))
                .when(records)
                .ready(any(), any());
        upload(UUID.randomUUID(), bytes).andExpect(status().isAccepted());
        verify(storage, never()).delete(anyString());
        assertThat(gallery().path("items").size()).isZero();
    }

    @Test
    void failedDeleteRemainsInvisibleAndRetries() throws Exception {
        var media = add();
        mvc.perform(
                        delete(base() + "/" + media.path("id").asText())
                                .header("Authorization", bearer))
                .andExpect(status().isNoContent());
        doThrow(new MediaStorageException()).when(storage).delete(anyString());
        service.cleanup();
        assertThat(jdbc.queryForObject("select state from product_media", String.class))
                .isEqualTo("DELETE_PENDING");
        assertThat(gallery().path("items").size()).isZero();
        reset(storage);
        jdbc.update("update product_media set next_cleanup_at=now()-interval '1 minute'");
        service.cleanup();
        assertThat(jdbc.queryForObject("select state from product_media", String.class))
                .isEqualTo("DELETED");
    }

    @Test
    void concurrentUploadsNeverExceedFive() throws Exception {
        for (int i = 0; i < 4; i++) add();
        var normalized = images.normalize(new java.io.ByteArrayInputStream(bytes), "image/png");
        var normalizedTogether = new CyclicBarrier(2);
        doAnswer(
                        inv -> {
                            normalizedTogether.await(20, TimeUnit.SECONDS);
                            return normalized;
                        })
                .when(images)
                .normalize(any(), anyString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<Integer> call =
                    () -> {
                        barrier.await();
                        return upload(UUID.randomUUID(), bytes)
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var a = executor.submit(call);
            var b = executor.submit(call);
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from product_media where position is not null",
                                Integer.class))
                .isEqualTo(5);
    }

    @Test
    void failedReservationNeverWritesStorage() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic failure"))
                .when(records)
                .insert(any());
        try {
            upload(UUID.randomUUID(), bytes);
        } catch (jakarta.servlet.ServletException expected) {
        }
        verify(storage, never()).put(any(), any());
        assertThat(jdbc.queryForObject("select count(*) from product_media", Integer.class))
                .isZero();
    }

    @Test
    void concurrentCleanupClaimsNeverOwnTheSameAsset() throws Exception {
        add();
        add();
        jdbc.update(
                "update product_media set state='DELETE_PENDING',position=null,next_cleanup_at=now()-interval '1 minute'");
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var bothClaimed = new CyclicBarrier(2);
            Callable<UUID> claim =
                    () ->
                            transaction.execute(
                                    s -> {
                                        var owned =
                                                records.claimCleanup(Instant.now()).orElseThrow();
                                        try {
                                            bothClaimed.await(20, TimeUnit.SECONDS);
                                        } catch (Exception ex) {
                                            throw new IllegalStateException(ex);
                                        }
                                        return owned.media().id();
                                    });
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            assertThat(first.get(30, TimeUnit.SECONDS))
                    .isNotEqualTo(second.get(30, TimeUnit.SECONDS));
        }
        var next = transaction.execute(s -> records.claimCleanup(Instant.now()));
        assertThat(next).isEmpty();
    }

    @Test
    void rejectsMultiplePartsAndBadFinanciallyUnrelatedPayloads() throws Exception {
        mvc.perform(
                        multipart(base())
                                .file(new MockMultipartFile("file", "a.png", "image/png", bytes))
                                .file(new MockMultipartFile("file", "b.png", "image/png", bytes))
                                .header("Authorization", bearer)
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart(base())
                                .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", bytes))
                                .header("Authorization", bearer)
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isUnsupportedMediaType());
    }
}

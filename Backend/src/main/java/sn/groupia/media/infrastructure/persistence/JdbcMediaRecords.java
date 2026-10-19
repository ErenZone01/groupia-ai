package sn.groupia.media.infrastructure.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import sn.groupia.media.domain.model.*;
import sn.groupia.media.domain.repository.MediaRecords;

@Repository
class JdbcMediaRecords implements MediaRecords {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcMediaRecords(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp time(Instant value) {
        return Timestamp.from(value);
    }

    @Override
    public long lockGallery(UUID org, UUID product, Instant now) {
        var p = Map.of("org", org, "product", product, "now", time(now));
        jdbc.update(
                "insert into product_media_galleries(product_id,organization_id,version,created_at,updated_at) values(:product,:org,0,:now,:now) on conflict(product_id) do nothing",
                p);
        return jdbc.queryForObject(
                "select version from product_media_galleries where organization_id=:org and product_id=:product for update",
                p,
                Long.class);
    }

    @Override
    public Map<UUID, Long> versions(UUID org, Set<UUID> products) {
        Map<UUID, Long> result = new HashMap<>();
        if (!products.isEmpty())
            jdbc.query(
                    "select product_id,version from product_media_galleries where organization_id=:org and product_id in (:ids)",
                    Map.of("org", org, "ids", products),
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            r -> result.put(r.getObject(1, UUID.class), r.getLong(2)));
        return result;
    }

    @Override
    public List<ProductMedia> list(UUID org, Set<UUID> products) {
        if (products.isEmpty()) return List.of();
        return jdbc.query(
                "select * from product_media where organization_id=:org and product_id in (:ids) and state in ('READY','UPLOADING') order by position,created_at,id",
                Map.of("org", org, "ids", products),
                mapper);
    }

    @Override
    public Optional<ProductMedia> findIdempotency(UUID org, UUID product, UUID key) {
        return jdbc
                .query(
                        "select * from product_media where organization_id=:org and product_id=:product and idempotency_key=:key",
                        Map.of("org", org, "product", product, "key", key),
                        mapper)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ProductMedia> find(UUID org, UUID product, UUID id) {
        return jdbc
                .query(
                        "select * from product_media where organization_id=:org and product_id=:product and id=:id",
                        Map.of("org", org, "product", product, "id", id),
                        mapper)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ProductMedia> findPublic(UUID id) {
        return jdbc
                .query(
                        "select * from product_media where id=:id and state='READY'",
                        Map.of("id", id),
                        mapper)
                .stream()
                .findFirst();
    }

    @Override
    public void insert(ProductMedia m) {
        var p =
                new MapSqlParameterSource()
                        .addValue("id", m.id())
                        .addValue("org", m.organizationId())
                        .addValue("product", m.productId())
                        .addValue("pos", m.position())
                        .addValue("idem", m.idempotencyKey())
                        .addValue("finger", m.fingerprint())
                        .addValue("now", time(m.createdAt()))
                        .addValue("expires", time(m.uploadExpiresAt()));
        rendition(p, "d", m.display());
        rendition(p, "t", m.thumbnail());
        jdbc.update(
                """
                insert into product_media(id,organization_id,product_id,state,position,
                display_storage_key,display_content_type,display_width,display_height,display_size_bytes,display_sha256,
                thumbnail_storage_key,thumbnail_content_type,thumbnail_width,thumbnail_height,thumbnail_size_bytes,thumbnail_sha256,
                idempotency_key,request_fingerprint,processing_version,upload_expires_at,next_cleanup_at,created_at,updated_at)
                values(:id,:org,:product,'UPLOADING',:pos,:dk,:dc,:dw,:dh,:ds,:df,:tk,:tc,:tw,:th,:ts,:tf,:idem,:finger,1,:expires,:expires,:now,:now)
                """,
                p);
    }

    private void rendition(MapSqlParameterSource p, String prefix, MediaRendition r) {
        p.addValue(prefix + "k", r.key())
                .addValue(prefix + "c", r.contentType())
                .addValue(prefix + "w", r.width())
                .addValue(prefix + "h", r.height())
                .addValue(prefix + "s", r.sizeBytes())
                .addValue(prefix + "f", r.sha256());
    }

    @Override
    public void ready(UUID id, Instant now) {
        jdbc.update(
                "update product_media set state='READY',next_cleanup_at=null,cleanup_token=null,updated_at=:now where id=:id and state='UPLOADING'",
                Map.of("id", id, "now", time(now)));
    }

    @Override
    public void removing(UUID id, Instant now, String reason) {
        jdbc.update(
                "update product_media set state='DELETE_PENDING',position=null,next_cleanup_at=case when cleanup_token is null then :now else next_cleanup_at end,updated_at=:now,last_error_code=:reason where id=:id and state in ('READY','UPLOADING')",
                new MapSqlParameterSource("id", id)
                        .addValue("now", time(now))
                        .addValue("reason", reason));
    }

    @Override
    public void order(UUID product, List<UUID> ids, Instant now) {
        jdbc.getJdbcTemplate().execute("set constraints uk_media_position deferred");
        for (int i = 0; i < ids.size(); i++)
            jdbc.update(
                    "update product_media set position=:pos,updated_at=:now where product_id=:product and id=:id and state in ('READY','UPLOADING')",
                    Map.of("pos", i, "id", ids.get(i), "product", product, "now", time(now)));
        jdbc.update(
                "update product_media_galleries set version=version+1,updated_at=:now where product_id=:product",
                Map.of("now", time(now), "product", product));
    }

    @Override
    public Optional<CleanupClaim> claimCleanup(Instant now) {
        UUID token = UUID.randomUUID();
        var items =
                jdbc.query(
                        """
                update product_media set cleanup_token=:token,next_cleanup_at=:lease,cleanup_attempts=cleanup_attempts+1
                where id=(select id from product_media where next_cleanup_at<=:now order by next_cleanup_at,id for update skip locked limit 1)
                returning *
                """,
                        Map.of(
                                "token",
                                token,
                                "lease",
                                time(now.plusSeconds(120)),
                                "now",
                                time(now)),
                        mapper);
        return items.stream().findFirst().map(m -> new CleanupClaim(m, token));
    }

    @Override
    public void cleaned(UUID id, UUID token, Instant now) {
        jdbc.update(
                """
                update product_media set state='DELETED',deleted_at=coalesce(deleted_at,:now),updated_at=:now,
                next_cleanup_at=case when upload_expires_at>:cutoff then cast(:again as timestamptz) else null end,cleanup_token=null,last_error_code=null
                where id=:id and cleanup_token=:token and state in ('DELETE_PENDING','DELETED')
                """,
                Map.of(
                        "id",
                        id,
                        "token",
                        token,
                        "now",
                        time(now),
                        "cutoff",
                        time(now.minusSeconds(604800)),
                        "again",
                        time(now.plusSeconds(86400))));
    }

    @Override
    public void retryCleanup(UUID id, UUID token, Instant now) {
        jdbc.update(
                "update product_media set next_cleanup_at=:next,cleanup_token=null,last_error_code='MEDIA_STORAGE_UNAVAILABLE' where id=:id and cleanup_token=:token",
                Map.of("id", id, "token", token, "next", time(now.plusSeconds(300))));
    }

    private static MediaRendition readRendition(ResultSet r, String prefix) throws SQLException {
        return new MediaRendition(
                r.getString(prefix + "_storage_key"),
                r.getString(prefix + "_content_type"),
                r.getInt(prefix + "_width"),
                r.getInt(prefix + "_height"),
                r.getLong(prefix + "_size_bytes"),
                r.getString(prefix + "_sha256"));
    }

    private static final RowMapper<ProductMedia> mapper =
            (r, n) ->
                    new ProductMedia(
                            r.getObject("id", UUID.class),
                            r.getObject("organization_id", UUID.class),
                            r.getObject("product_id", UUID.class),
                            ProductMedia.State.valueOf(r.getString("state")),
                            (Integer) r.getObject("position", Integer.class),
                            readRendition(r, "display"),
                            readRendition(r, "thumbnail"),
                            r.getObject("idempotency_key", UUID.class),
                            r.getString("request_fingerprint"),
                            r.getTimestamp("created_at").toInstant(),
                            r.getTimestamp("upload_expires_at").toInstant(),
                            r.getInt("cleanup_attempts"),
                            r.getString("last_error_code"));
}

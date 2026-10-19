# 0029 — Product Media V1

## Decision

Media owns the product gallery, metadata, processing and storage lifecycle. Catalog remains independent. Media depends only on catalog::api, organization::api and shared::api. CatalogProducts verifies tenant ownership and obtains a product row lock in the caller's short transaction. No cross-module JPA relationship exists.

PostgreSQL is authoritative. Migration 029 adds galleries and product_media; migrations 001–028 remain immutable. A composite FK uses the existing product tenant key. Five unique, deferred positions constrain READY + UPLOADING, including concurrent reservations. The first READY position is primary; no redundant boolean. Gallery version is independent from Product.version.

## Storage and distribution

The provider-neutral MediaObjectStorage port has a local filesystem adapter and a synchronous S3-compatible adapter. Cloudflare R2 Standard is the recommended initial cloud provider, not a domain dependency. AWS SDK implements the S3 protocol without requiring an AWS account. Development and standard tests require no cloud account, credentials or expense.

Media is disabled by default. Enabled production accepts only fully configured S3 storage. Explicit credentials are currently supported; ambient identity chains are not implicitly enabled. Chunked encoding defaults off for R2 compatibility. Public distribution origin is distinct from the S3 API endpoint.

Only normalized display/thumbnail URLs are returned after READY. The local byte route checks READY on every request. The user explicitly accepted direct public object URLs and residual CDN/browser caches after deletion. Public storage is not an authorization boundary: origin access must be configured deliberately, and raw originals are never uploaded. Future custom domain changes require configuration, not a DB migration. Configure nosniff at the CDN; S3 object metadata cannot set that response header portably.

## Processing

JPEG, static PNG and static WebP are accepted. Only JPEG and PNG are emitted. EXIF orientation (including mirrors) is applied, pixels converted to sRGB, and source EXIF/XMP/comments discarded. Actual transparency produces PNG; opaque pixels produce JPEG. No native library or WebP encoder. A non-waiting local semaphore bounds heavy processing to one image per instance. Incoming bytes, dimensions, pixel count and output bytes are capped; no original is retained.

## Transactions and recovery

Normalize before reservation. A short transaction persists an UPLOADING asset, expected hashes and generated keys before any object PUT. Storage calls occur outside transactions. Publication rechecks the active product and permission, locks product then gallery, marks READY and compacts ready assets before pending reservations. A lost response is recovered using the UUID idempotency key and input fingerprint. Different input with the same key conflicts.

An uncertain write is checked with HEAD. If both expected objects cannot be proven present, return 202 with operation ID, no public URLs. A database finalization error must never cause blind object deletion: commit may already have succeeded. Pending uploads expire after 15 minutes and are removed; V1 does not automatically resume missing byte uploads after process loss.

Delete commits DELETE_PENDING and compaction before object deletion. A bounded scheduled job claims one operation at a time with SKIP LOCKED and a two-minute lease/token; at most 100 per run. Object absence is successful deletion. Failure schedules retry after five minutes. Tombstones are checked daily for seven days beyond upload expiry to remove late writes; storage calls have a 30-second total timeout. No global lock, distributed broker or transaction around S3.

## Tradeoffs and risks

Public immutable caches do not provide instantaneous revocation. R2 itself still needs preproduction contract testing; CI uses a local S3 HTTP contract server. CPU/memory limits must be measured with production heap settings. Metadata parsing/decoding libraries must remain patched. Object storage and DB backups require coordinated operational restore. Future historical media references need a retention rule before reuse in immutable order records. Generic media library, storefront, direct browser upload and advanced editing are outside V1.

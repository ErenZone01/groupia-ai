# Product Media V1 operations

## Free local development

The feature defaults to disabled. Existing application configuration (database/auth) is still required; no media cloud account is required.

```properties
GROUPIA_MEDIA_ENABLED=true
GROUPIA_MEDIA_STORAGE_TYPE=filesystem
GROUPIA_MEDIA_LOCAL_ROOT=.groupia-media
```

The relative directory is resolved from the backend working directory and ignored by Git. Only generated normalized objects are persisted. Angular loads `/api/public/media/{mediaId}/display` or `/thumbnail` through its existing same-origin API proxy. The route resolves READY metadata, never a caller-supplied filesystem path. Do not expose the directory through a web server. Restrict filesystem access to the application OS user.

## Future preproduction / production

```properties
GROUPIA_MEDIA_ENABLED=true
GROUPIA_MEDIA_STORAGE_TYPE=s3
GROUPIA_MEDIA_S3_ENDPOINT=
GROUPIA_MEDIA_S3_REGION=auto
GROUPIA_MEDIA_S3_BUCKET=
GROUPIA_MEDIA_S3_ACCESS_KEY=
GROUPIA_MEDIA_S3_SECRET_KEY=
GROUPIA_MEDIA_S3_PATH_STYLE_ACCESS=true
GROUPIA_MEDIA_S3_CHUNKED_ENCODING_ENABLED=false
GROUPIA_MEDIA_PUBLIC_BASE_URL=
```

Initial provider: Cloudflare R2 Standard. Supply endpoint, bucket, credentials and HTTPS distribution URL through deployment secrets/configuration. No account or real credentials are needed for this change's tests. The SDK supports configurable endpoints and path-style addressing for other S3 implementations. `prod + enabled` rejects filesystem/memory and incomplete S3 configuration. Disabled production starts without media credentials.

S3 API endpoint and public URL are not interchangeable. For a future custom domain such as an images subdomain, change only PUBLIC_BASE_URL. Set the same configured public base URL on the frontend production server so its CSP allows exactly that HTTPS origin. CSP also permits blob previews. No wildcard scheme or domain.

Only display/thumbnail URLs for READY assets are exposed by the API. Images are intended to be public by URL. Raw originals and intermediates never enter object storage. Configure the origin/CDN accordingly: no public writes, no bucket listing, least-privilege application key, exact image Content-Type, inline disposition, immutable cache and `X-Content-Type-Options: nosniff` at the CDN. A deleted image disappears from the gallery immediately but old public/cache URLs are not instantly revoked (explicitly accepted V1 behavior). A private draft-image requirement needs a different distribution policy.

## Limits and lifecycle

Input: 8 MiB; multipart request: 9 MiB; one file/request. Width/height: 6000 max; pixels: 24,000,000 max. Display: 2000px / 4 MiB; thumbnail: 480px / 512 KiB. No upscaling. JPEG quality 0.85, PNG for transparency. JPEG/static PNG/static WebP only. EXIF orientation is applied and original metadata is discarded. CPU concurrency is one, with overload rejected rather than queued indefinitely.

The typed `groupia.media.*` limits can be lowered, but hard ceilings cannot be raised inadvertently. Environment mappings cover input bytes/dimensions/pixels. Other limits use Spring external properties. Do not change webhook/auth request limits to enable image uploads.

Uploads are sequential in the UI. Each has an Idempotency-Key UUID. Keep that key for retries of identical bytes. A 202 has a media ID: query the operation before submitting another upload. After an abandoned/expired upload, remove its preview and select the file again to obtain a new operation. READY publication rechecks product status; archiving prevents upload/reorder but permits deletion.

## Recovery and monitoring

The job handles at most 100 due operations per run, initially every minute. It uses short DB claims and calls storage outside transactions. Failed deletions remain invisible and retry. Expired uploads are deleted, not blindly published. Do not manually mark uncertain assets READY. Never delete all objects under a product prefix without checking authoritative rows.

Monitor `groupia.media.upload.success`, `groupia.media.upload.failure`, `groupia.media.processing.duration`, `groupia.media.cleanup.failure`; no tenant/product/filename metric labels. Query counts/age of UPLOADING and DELETE_PENDING operationally without logging content or credentials. Investigate repeating failures (permissions, outage, configuration). Tombstones repeat deletion for seven days to cover late transfers. A complete storage inventory audit and recovery after manual DB restore remain operational follow-up work; do not apply lifecycle expiry to all READY keys.

Back up metadata and object storage with compatible retention. Normalized objects are immutable. Do not print configuration beans, SDK wire logs, headers, EXIF or signed URLs; settings redact credentials and application storage exceptions contain only a safe code.

## APIs

Under `/api/organizations/{organizationId}/products/{productId}/media`: GET gallery, POST one multipart `file`, GET `/{mediaId}` operation, DELETE `/{mediaId}`, PUT `/order` with version and complete ordered READY IDs. Batch: GET `/api/organizations/{organizationId}/product-media?productIds=...`, max 100 unique IDs. GET never creates galleries. Reads require VIEW_CATALOG; mutations MANAGE_CATALOG. Management APIs remain Bearer authenticated.

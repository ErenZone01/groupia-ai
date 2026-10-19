package sn.groupia.media.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import sn.groupia.media.domain.model.MediaRendition;
import sn.groupia.media.domain.service.*;
import sn.groupia.media.infrastructure.MediaProperties;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

public final class S3MediaStorage implements MediaObjectStorage, AutoCloseable {
    private final S3Client client;
    private final String bucket;
    private final String publicBaseUrl;

    public S3MediaStorage(MediaProperties properties) {
        var config = properties.s3();
        bucket = config.bucket();
        publicBaseUrl = properties.publicBaseUrl().replaceAll("/+$", "");
        client =
                S3Client.builder()
                        .endpointOverride(URI.create(config.endpoint()))
                        .region(Region.of(config.region()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                config.accessKey(), config.secretKey())))
                        .httpClientBuilder(
                                UrlConnectionHttpClient.builder()
                                        .connectionTimeout(Duration.ofSeconds(3))
                                        .socketTimeout(Duration.ofSeconds(15)))
                        .overrideConfiguration(
                                c ->
                                        c.apiCallTimeout(Duration.ofSeconds(30))
                                                .apiCallAttemptTimeout(Duration.ofSeconds(15)))
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(config.pathStyleAccess())
                                        .chunkedEncodingEnabled(config.chunkedEncodingEnabled())
                                        .build())
                        .build();
    }

    @Override
    public void put(MediaRendition rendition, byte[] bytes) {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(MediaKeys.require(rendition.key()))
                            .contentType(rendition.contentType())
                            .contentLength((long) bytes.length)
                            .contentDisposition("inline")
                            .cacheControl("public, max-age=31536000, immutable")
                            .metadata(Map.of("sha256", rendition.sha256()))
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (RuntimeException ex) {
            throw new MediaStorageException();
        }
    }

    @Override
    public Optional<StoredObject> head(String key) {
        try {
            var value =
                    client.headObject(
                            HeadObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(MediaKeys.require(key))
                                    .build());
            return Optional.of(
                    new StoredObject(value.contentLength(), value.metadata().get("sha256")));
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) return Optional.empty();
            throw new MediaStorageException();
        } catch (RuntimeException ex) {
            throw new MediaStorageException();
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucket)
                            .key(MediaKeys.require(key))
                            .build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) throw new MediaStorageException();
        } catch (RuntimeException ex) {
            throw new MediaStorageException();
        }
    }

    @Override
    public String resolveUrl(String key) {
        return publicBaseUrl + "/" + MediaKeys.require(key);
    }

    @Override
    public void close() {
        client.close();
    }
}

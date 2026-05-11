package com.stocka.backend.modules.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Cloudflare R2 implementation of {@link R2Service}, talking to the bucket through the
 * S3-compatible API exposed at {@link R2Properties#getEndpoint()}. Active when
 * {@code stocka.r2.use-local=false}.
 *
 * <p>Clients ({@link S3Client}, {@link S3Presigner}) are built lazily on first use so the
 * application can boot even without R2 credentials configured (in that case the first
 * operation will throw {@link R2UnavailableException}, returning HTTP 503).
 */
@Service
@ConditionalOnProperty(name = "stocka.r2.use-local", havingValue = "false")
public class R2StorageServiceImpl implements R2Service {
    private static final Logger log = LoggerFactory.getLogger(R2StorageServiceImpl.class);

    private final R2Properties properties;
    private volatile S3Client client;
    private volatile S3Presigner presigner;

    public R2StorageServiceImpl(R2Properties properties) {
        this.properties = properties;
    }

    @Override
    public UploadedObject upload(String key, InputStream stream, long sizeBytes, String mimeType) {
        try {
            client().putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(mimeType)
                            .contentLength(sizeBytes)
                            .build(),
                    RequestBody.fromInputStream(stream, sizeBytes)
            );
            return new UploadedObject(key, sizeBytes, mimeType);
        } catch (S3Exception e) {
            throw new R2UnavailableException("Fallo al subir objeto a R2: " + key, e);
        }
    }

    @Override
    public PresignedDownload presign(String key, Duration ttl, String contentDisposition) {
        try {
            GetObjectRequest.Builder getObjectRequest = GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key);
            if (contentDisposition != null && !contentDisposition.isBlank()) {
                getObjectRequest.responseContentDisposition(contentDisposition);
            }
            PresignedGetObjectRequest presigned = presigner().presignGetObject(
                    GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(getObjectRequest.build())
                            .build()
            );
            return new PresignedDownload(presigned.url().toString(), Instant.now().plus(ttl));
        } catch (S3Exception e) {
            throw new R2UnavailableException("Fallo al firmar URL de descarga: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
        } catch (S3Exception e) {
            throw new R2UnavailableException("Fallo al eliminar objeto en R2: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client().headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            throw new R2UnavailableException("Fallo al consultar objeto en R2: " + key, e);
        }
    }

    @Override
    public String buildPieceKey(int orgId, int pieceId, String originalFilename) {
        return R2Keys.buildPieceKey(orgId, pieceId, originalFilename);
    }

    private S3Client client() {
        S3Client local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = buildClient();
                    client = local;
                }
            }
        }
        return local;
    }

    private S3Presigner presigner() {
        S3Presigner local = presigner;
        if (local == null) {
            synchronized (this) {
                local = presigner;
                if (local == null) {
                    local = buildPresigner();
                    presigner = local;
                }
            }
        }
        return local;
    }

    private S3Client buildClient() {
        validateConfig();
        log.info("initializing R2 S3 client endpoint={} bucket={}", properties.getEndpoint(), properties.getBucket());
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private S3Presigner buildPresigner() {
        validateConfig();
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private void validateConfig() {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()
                || properties.getAccessKey() == null || properties.getAccessKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()
                || properties.getBucket() == null || properties.getBucket().isBlank()) {
            throw new R2UnavailableException("R2 no está configurado: revisa endpoint, bucket y credenciales");
        }
    }
}

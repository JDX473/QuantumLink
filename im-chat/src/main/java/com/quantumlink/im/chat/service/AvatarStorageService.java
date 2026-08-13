package com.quantumlink.im.chat.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.UUID;

/**
 * 头像存储服务(MinIO 对象存储)。
 *
 * <p>职责:上传头像文件到 MinIO bucket,返回可访问的 URL。
 * <ul>
 *   <li>bucket 不存在则自动创建;</li>
 *   <li>对象 key 用 {@code avatar/{userId}.{ext}},同名覆盖(改头像);</li>
 *   <li>返回 URL:{@code endpoint/bucket/{key}}。</li>
 * </ul>
 */
@Slf4j
@Service
public class AvatarStorageService {

    private final MinioClient minioClient;
    private final String endpoint;
    private final String bucket;

    public AvatarStorageService(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.accessKey}") String accessKey,
            @Value("${minio.secretKey}") String secretKey,
            @Value("${minio.bucket}") String bucket) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        log.info("MinIO config: endpoint={} accessKey={} bucket={}", endpoint, accessKey, bucket);
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket created: {}", bucket);
            }
        } catch (Exception e) {
            // 可降级:MinIO 不可用时仅告警、不阻断启动;头像上传时才真正报错
            log.warn("MinIO bucket init failed, 头像功能将不可用(服务继续启动): {}", e.getMessage());
        }
    }

    /**
     * 上传头像。
     *
     * @param userId  用户 ID(对象 key 的一部分)
     * @param data    文件字节
     * @param contentType MIME 类型(如 image/jpeg)
     * @return 头像访问 URL
     */
    public String uploadAvatar(String userId, byte[] data, String contentType) {
        // 从 MIME 推断扩展名
        String ext = switch (contentType == null ? "" : contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        String objectKey = userId + "." + ext;
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType == null ? "image/jpeg" : contentType)
                    .build());
            log.info("avatar uploaded: {}/{}", bucket, objectKey);
            // URL = endpoint/bucket/objectKey(不含重复 bucket)
            return endpoint + "/" + bucket + "/" + objectKey;
        } catch (Exception e) {
            throw new IllegalStateException("avatar upload failed: " + objectKey, e);
        }
    }
}

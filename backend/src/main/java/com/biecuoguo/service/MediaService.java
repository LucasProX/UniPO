package com.biecuoguo.service;

import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.MediaObject;
import com.biecuoguo.dto.MediaDtos;
import com.biecuoguo.mapper.MediaObjectMapper;
import com.biecuoguo.security.CurrentUser;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final AppProperties properties;
    private final MediaObjectMapper mediaObjectMapper;

    public MediaService(AppProperties properties, MediaObjectMapper mediaObjectMapper) {
        this.properties = properties;
        this.mediaObjectMapper = mediaObjectMapper;
    }

    public MediaDtos.UploadResponse upload(MultipartFile file, String purpose, CurrentUser currentUser) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException("目前只支持图片上传");
        }
        try {
            AppProperties.Minio minio = properties.minio();
            MinioClient client = client();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(minio.bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(minio.bucket()).build());
            }
            String ext = extension(file.getOriginalFilename());
            String key = firstNonBlank(purpose, "post") + "/" + currentUser.id() + "/" + UUID.randomUUID() + ext;
            client.putObject(PutObjectArgs.builder()
                    .bucket(minio.bucket())
                    .object(key)
                    .contentType(contentType)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .build());

            LocalDateTime now = LocalDateTime.now();
            MediaObject media = new MediaObject();
            media.setUserId(currentUser.id());
            media.setBucket(minio.bucket());
            media.setObjectKey(key);
            media.setUrl("minio://" + minio.bucket() + "/" + key);
            media.setContentType(contentType);
            media.setSizeBytes(file.getSize());
            media.setPurpose(firstNonBlank(purpose, "post"));
            media.setCreatedAt(now);
            mediaObjectMapper.insert(media);
            media.setUrl(mediaUrl(media));
            mediaObjectMapper.updateById(media);
            return new MediaDtos.UploadResponse(media.getId(), media.getUrl(), media.getObjectKey(), media.getContentType(), media.getSizeBytes(), media.getPurpose(), media.getCreatedAt());
        } catch (Exception ex) {
            throw uploadFailure(ex);
        }
    }

    public MediaDtos.StoredObject open(Long mediaId) {
        MediaObject media = mediaObjectMapper.selectById(mediaId);
        if (media == null) {
            throw new NoSuchElementException("文件不存在");
        }
        try {
            MinioClient client = client();
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(media.getBucket())
                    .object(media.getObjectKey())
                    .build());
            InputStream stream = client.getObject(GetObjectArgs.builder()
                    .bucket(media.getBucket())
                    .object(media.getObjectKey())
                    .build());
            String contentType = firstNonBlank(firstNonBlank(media.getContentType(), stat.contentType()), "application/octet-stream");
            return new MediaDtos.StoredObject(stream, contentType, stat.size());
        } catch (Exception ex) {
            throw new NoSuchElementException("文件不存在或无法读取");
        }
    }

    private MinioClient client() {
        AppProperties.Minio minio = properties.minio();
        return MinioClient.builder()
                .endpoint(minio.endpoint())
                .credentials(minio.accessKey(), minio.secretKey())
                .build();
    }

    private IllegalArgumentException uploadFailure(Exception ex) {
        AppProperties.Minio minio = properties.minio();
        log.warn("MinIO upload failed. endpoint={}, bucket={}, publicRead={}, accessKeySet={}, error={}: {}",
                minio.endpoint(),
                minio.bucket(),
                minio.publicRead(),
                minio.accessKey() != null && !minio.accessKey().isBlank(),
                ex.getClass().getSimpleName(),
                ex.getMessage(),
                ex);
        return new IllegalArgumentException("上传失败：" + uploadFailureMessage(ex, minio));
    }

    private String uploadFailureMessage(Exception ex, AppProperties.Minio minio) {
        if (isBlank(minio.endpoint())) {
            return "MinIO 地址没有配置";
        }
        if (isBlank(minio.accessKey()) || isBlank(minio.secretKey())) {
            return "MinIO 访问密钥没有配置";
        }
        if (isBlank(minio.bucket())) {
            return "MinIO bucket 没有配置";
        }
        if (ex instanceof ErrorResponseException error) {
            String code = error.errorResponse().code();
            String message = firstNonBlank(error.errorResponse().message(), error.getMessage());
            if ("InvalidAccessKeyId".equalsIgnoreCase(code)) {
                return "MinIO access key 不存在，请检查 MINIO_ACCESS_KEY";
            }
            if ("SignatureDoesNotMatch".equalsIgnoreCase(code)) {
                return "MinIO secret key 不匹配，请检查 MINIO_SECRET_KEY";
            }
            if ("AccessDenied".equalsIgnoreCase(code)) {
                return "MinIO 访问密钥没有 bucket 写入权限";
            }
            if ("NoSuchBucket".equalsIgnoreCase(code)) {
                return "MinIO bucket 不存在，且当前密钥不能自动创建";
            }
            return "MinIO 返回 " + code + "：" + message;
        }
        if (hasCause(ex, ConnectException.class) || hasCause(ex, UnknownHostException.class)) {
            return "无法连接 MinIO 服务，请检查 MINIO_ENDPOINT";
        }
        return firstNonBlank(ex.getMessage(), "请查看后端日志中的 MinIO 详细错误");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String mediaUrl(MediaObject media) {
        AppProperties.Minio minio = properties.minio();
        if (minio.publicRead()) {
            return trimRight(minio.publicEndpoint()) + "/" + media.getBucket() + "/" + media.getObjectKey();
        }
        return "/api/media/" + media.getId() + "/content?v=" + media.getObjectKey().substring(media.getObjectKey().lastIndexOf('/') + 1);
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.'));
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimRight(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}

package com.biecuoguo.service;

import com.biecuoguo.config.AppTime;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** 认不出来的图片一律拒绝时的提示。故意不含 SVG：它是 XML，能带脚本。 */
    private static final String SUPPORTED_HINT = "只支持 JPEG / PNG / GIF / WebP 图片（按文件内容识别，改扩展名没用）";

    /** purpose 会拼进对象路径，只允许简单的字母数字，免得被拿来构造奇怪的 key。 */
    private static final java.util.regex.Pattern PURPOSE_PATTERN = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,20}");

    private final AppProperties properties;
    private final MediaObjectMapper mediaObjectMapper;
    private final long maxImageBytes;

    public MediaService(AppProperties properties, MediaObjectMapper mediaObjectMapper,
                        @Value("${spring.servlet.multipart.max-file-size:5MB}") String maxFileSize) {
        this.properties = properties;
        this.mediaObjectMapper = mediaObjectMapper;
        this.maxImageBytes = parseSize(maxFileSize, 5L * 1024 * 1024);
    }

    public MediaDtos.UploadResponse upload(MultipartFile file, String purpose, CurrentUser currentUser) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > maxImageBytes) {
            throw new IllegalArgumentException("图片不能超过 " + humanSize(maxImageBytes) + "，请换一张更小的");
        }
        // 类型只看文件头。Content-Type 和扩展名都是客户端说了算的，
        // 以前就是因为只信 Content-Type，随便一个文件改个请求头就能传上来。
        ImageSignature.Kind kind;
        try (InputStream head = file.getInputStream()) {
            kind = ImageSignature.detect(head);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取上传文件失败，请重试");
        }
        if (kind == null) {
            throw new IllegalArgumentException(SUPPORTED_HINT);
        }
        String contentType = kind.contentType();
        try {
            AppProperties.Minio minio = properties.minio();
            MinioClient client = client();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(minio.bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(minio.bucket()).build());
            }
            String key = safePurpose(purpose) + "/" + currentUser.id() + "/" + UUID.randomUUID() + "." + kind.extension();
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
            media.setPurpose(safePurpose(purpose));
            media.setCreatedAt(now);
            mediaObjectMapper.insert(media);
            media.setUrl(mediaUrl(media));
            mediaObjectMapper.updateById(media);
            return new MediaDtos.UploadResponse(media.getId(), media.getUrl(), media.getObjectKey(), media.getContentType(), media.getSizeBytes(), media.getPurpose(), AppTime.toOffset(media.getCreatedAt()));
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

    private String safePurpose(String purpose) {
        String value = purpose == null ? "" : purpose.trim();
        return PURPOSE_PATTERN.matcher(value).matches() ? value : "post";
    }

    /** 把配置里的 "5MB" 之类解析成字节数；解析不了就用兜底值，绝不让它变成 0。 */
    private long parseSize(String value, long fallback) {
        try {
            long parsed = DataSize.parse(value).toBytes();
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String humanSize(long bytes) {
        return bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0
                ? (bytes / (1024 * 1024)) + "MB"
                : (bytes / 1024) + "KB";
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimRight(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}

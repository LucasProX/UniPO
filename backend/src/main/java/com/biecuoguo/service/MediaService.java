package com.biecuoguo.service;

import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.MediaObject;
import com.biecuoguo.dto.MediaDtos;
import com.biecuoguo.mapper.MediaObjectMapper;
import com.biecuoguo.security.CurrentUser;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class MediaService {
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
            MinioClient client = MinioClient.builder()
                    .endpoint(minio.endpoint())
                    .credentials(minio.accessKey(), minio.secretKey())
                    .build();
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
            media.setUrl(trimRight(minio.publicEndpoint()) + "/" + minio.bucket() + "/" + key);
            media.setContentType(contentType);
            media.setSizeBytes(file.getSize());
            media.setPurpose(firstNonBlank(purpose, "post"));
            media.setCreatedAt(now);
            mediaObjectMapper.insert(media);
            return new MediaDtos.UploadResponse(media.getId(), media.getUrl(), media.getObjectKey(), media.getContentType(), media.getSizeBytes(), media.getPurpose(), media.getCreatedAt());
        } catch (Exception ex) {
            throw new IllegalArgumentException("上传失败，请确认 MinIO 已启动");
        }
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

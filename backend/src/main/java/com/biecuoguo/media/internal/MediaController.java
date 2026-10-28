package com.biecuoguo.media.internal;

import com.biecuoguo.media.api.MediaDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

@RestController
@RequestMapping("/api/media")
public class MediaController {
    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/upload")
    public ApiResponse<MediaDtos.UploadResponse> upload(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "post") String purpose) {
        return ApiResponse.ok(mediaService.upload(file, purpose, SecurityUtils.currentUser()));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable Long id, @RequestParam(required = false) String v,
                                                       @RequestParam(required = false) Integer size) {
        // size 是缩小图档位（短边像素：360 / 720 / 1080），不带就是原图。
        MediaDtos.StoredObject object = mediaService.open(id, size);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(object.contentType()))
                .contentLength(object.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline");
        if (v != null && !v.isBlank()) {
            response.cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable());
        } else {
            response.cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0");
        }
        return response.body(new InputStreamResource(object.stream()));
    }
}

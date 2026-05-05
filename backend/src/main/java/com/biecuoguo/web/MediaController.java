package com.biecuoguo.web;

import com.biecuoguo.dto.MediaDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.MediaService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
}

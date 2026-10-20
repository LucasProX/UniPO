package com.biecuoguo.spotlight.internal;

import com.biecuoguo.spotlight.api.SpotlightDtos;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 首页轮播位（公开只读）。
 *
 * <p>没有数据时返回空数组，前端据此不渲染——不要在这里塞默认运营文案。
 * 写入在 {@link AdminSpotlightController}。
 */
@RestController
@RequestMapping("/api/spotlights")
public class SpotlightController {

    private final SpotlightService spotlightService;

    public SpotlightController(SpotlightService spotlightService) {
        this.spotlightService = spotlightService;
    }

    @GetMapping
    public ApiResponse<List<SpotlightDtos.SpotlightView>> active() {
        return ApiResponse.ok(spotlightService.active());
    }
}

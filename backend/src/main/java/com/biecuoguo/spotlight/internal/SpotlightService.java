package com.biecuoguo.spotlight.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.spotlight.api.SpotlightAdminApi;
import com.biecuoguo.spotlight.api.SpotlightDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 首页轮播位。
 *
 * <p>读取路径刻意和写入路径分开：
 * <ul>
 *   <li>{@link #active()} 只返回「启用 + 在投放时间窗内」的条目，按权重倒序，最多 5 条；</li>
 *   <li>后台 {@link #listAll()} 返回全部（含已下线），供运营排查。</li>
 * </ul>
 *
 * <p>以后接算法推荐时，不要改公开接口：要么让算法任务往这张表写 {@code source='ALGORITHM'} 的行，
 * 要么在 {@link #active()} 里把算法结果和手动位合并。前者运维简单，后者实时性更好——
 * 现在先把数据结构留好，别在 API 层分叉。
 */
@Service
public class SpotlightService implements SpotlightAdminApi {

    private static final int MAX_ACTIVE = 5;

    private final SpotlightMapper spotlightMapper;

    public SpotlightService(SpotlightMapper spotlightMapper) {
        this.spotlightMapper = spotlightMapper;
    }

    /** 首页用：只取当前生效的，最多 5 条。 */
    public List<SpotlightDtos.SpotlightView> active() {
        LocalDateTime now = AppTime.nowLocal();
        return spotlightMapper.selectList(new LambdaQueryWrapper<Spotlight>()
                        .eq(Spotlight::getEnabled, true)
                        .and(w -> w.isNull(Spotlight::getStartsAt).or().le(Spotlight::getStartsAt, now))
                        .and(w -> w.isNull(Spotlight::getEndsAt).or().ge(Spotlight::getEndsAt, now))
                        .orderByDesc(Spotlight::getWeight)
                        .orderByDesc(Spotlight::getId)
                        .last("LIMIT " + MAX_ACTIVE))
                .stream().map(SpotlightService::toView).toList();
    }

    @Override
    public List<SpotlightDtos.SpotlightView> listAll() {
        return spotlightMapper.selectList(new LambdaQueryWrapper<Spotlight>()
                        .orderByDesc(Spotlight::getWeight)
                        .orderByDesc(Spotlight::getId))
                .stream().map(SpotlightService::toView).toList();
    }

    @Override
    @Transactional
    public SpotlightDtos.SpotlightView create(SpotlightDtos.UpsertRequest request, Long operatorId) {
        Spotlight entity = new Spotlight();
        apply(entity, request);
        entity.setCreatedBy(operatorId);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        spotlightMapper.insert(entity);
        return toView(entity);
    }

    @Override
    @Transactional
    public SpotlightDtos.SpotlightView update(Long id, SpotlightDtos.UpsertRequest request) {
        Spotlight entity = spotlightMapper.selectById(id);
        if (entity == null) {
            throw new NoSuchElementException("轮播位不存在");
        }
        apply(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        spotlightMapper.updateById(entity);
        return toView(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        spotlightMapper.deleteById(id);
    }

    private static void apply(Spotlight entity, SpotlightDtos.UpsertRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (request.title().length() > 80) {
            throw new IllegalArgumentException("标题最多 80 个字");
        }
        entity.setTitle(request.title().trim());
        entity.setSubtitle(trimToNull(request.subtitle(), 200, "副标题"));
        entity.setBadge(trimToNull(request.badge(), 20, "角标"));
        entity.setImageUrl(trimToNull(request.imageUrl(), 500, "图片地址"));
        entity.setLinkUrl(trimToNull(request.linkUrl(), 500, "跳转地址"));
        String source = request.source() == null || request.source().isBlank() ? "MANUAL" : request.source().trim();
        if (!"MANUAL".equals(source) && !"ALGORITHM".equals(source)) {
            throw new IllegalArgumentException("source 只能是 MANUAL 或 ALGORITHM");
        }
        entity.setSource(source);
        entity.setReferencePostId(request.referencePostId());
        entity.setWeight(request.weight() == null ? 0 : request.weight());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setStartsAt(request.startsAt() == null ? null : AppTime.toLocal(request.startsAt()));
        entity.setEndsAt(request.endsAt() == null ? null : AppTime.toLocal(request.endsAt()));
    }

    private static String trimToNull(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(label + "最多 " + max + " 个字");
        }
        return trimmed;
    }

    private static SpotlightDtos.SpotlightView toView(Spotlight entity) {
        return new SpotlightDtos.SpotlightView(
                entity.getId(),
                entity.getTitle(),
                entity.getSubtitle(),
                entity.getBadge(),
                entity.getImageUrl(),
                entity.getLinkUrl(),
                entity.getSource(),
                entity.getWeight(),
                entity.getEnabled(),
                entity.getStartsAt() == null ? null : AppTime.toOffset(entity.getStartsAt()),
                entity.getEndsAt() == null ? null : AppTime.toOffset(entity.getEndsAt()),
                entity.getCreatedAt() == null ? null : AppTime.toOffset(entity.getCreatedAt())
        );
    }
}

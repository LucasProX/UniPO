package com.biecuoguo.spotlight.api;

import java.util.List;

/** 管理端轮播位写操作。公开读取在 SpotlightController，不走这个接口。 */
public interface SpotlightAdminApi {

    List<SpotlightDtos.SpotlightView> listAll();

    SpotlightDtos.SpotlightView create(SpotlightDtos.UpsertRequest request, Long operatorId);

    SpotlightDtos.SpotlightView update(Long id, SpotlightDtos.UpsertRequest request);

    void delete(Long id);
}

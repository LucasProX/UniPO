package com.biecuoguo.achievement.internal;

import com.biecuoguo.achievement.api.TaskLookupApi;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TaskLookupService implements TaskLookupApi {
    private final AchievementTaskMapper taskMapper;

    public TaskLookupService(AchievementTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public Map<Long, String> titles(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        return taskMapper.selectBatchIds(taskIds).stream()
                .collect(Collectors.toMap(AchievementTask::getId, AchievementTask::getTitle));
    }
}

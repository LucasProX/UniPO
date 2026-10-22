package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.achievement.api.GrowthTerms;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.College;
import com.biecuoguo.domain.Major;
import com.biecuoguo.domain.School;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.dto.UserProfile;
import com.biecuoguo.mapper.CollegeMapper;
import com.biecuoguo.mapper.MajorMapper;
import com.biecuoguo.mapper.SchoolMapper;
import com.biecuoguo.mapper.UserMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/** 成长背景（学校、学院、专业、入学年份）的唯一写入口；归属关系在服务端校验。 */
@Service
public class GrowthBackgroundService {
    private final UserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final UserService userService;

    public GrowthBackgroundService(UserMapper userMapper, SchoolMapper schoolMapper, CollegeMapper collegeMapper,
                                   MajorMapper majorMapper, UserService userService) {
        this.userMapper = userMapper;
        this.schoolMapper = schoolMapper;
        this.collegeMapper = collegeMapper;
        this.majorMapper = majorMapper;
        this.userService = userService;
    }

    @Transactional
    public UserProfile update(Long userId, UserDtos.GrowthBackgroundRequest request) {
        User user = userMapper.lockById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        if (!"active".equals(user.getStatus())) {
            throw new AccessDeniedException("账号不可用");
        }
        LocalDate today = AppTime.nowLocal().toLocalDate();
        if (request.entryYear() == null || !GrowthTerms.plausibleEntryYear(request.entryYear(), today)) {
            throw new IllegalArgumentException("入学年份不正确");
        }
        Long schoolId = request.schoolId() == null ? user.getSchoolId() : request.schoolId();
        School school = schoolId == null ? null : schoolMapper.selectById(schoolId);
        if (school == null || !"active".equals(school.getStatus())) {
            throw new IllegalArgumentException("请选择有效的学校");
        }
        Major major = request.majorId() == null ? null : majorMapper.selectById(request.majorId());
        if (request.majorId() != null && (major == null || !schoolId.equals(major.getSchoolId()))) {
            throw new IllegalArgumentException("专业不属于所选学校");
        }
        Long collegeId = request.collegeId() != null ? request.collegeId() : major == null ? null : major.getCollegeId();
        College college = collegeId == null ? null : collegeMapper.selectById(collegeId);
        if (collegeId != null && (college == null || !schoolId.equals(college.getSchoolId()))) {
            throw new IllegalArgumentException("学院不属于所选学校");
        }
        if (major != null && college != null && major.getCollegeId() != null && !college.getId().equals(major.getCollegeId())) {
            throw new IllegalArgumentException("专业不属于所选学院");
        }
        int term = GrowthTerms.termIndex(request.entryYear(), today);
        LocalDateTime now = AppTime.nowLocal();
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getSchoolId, schoolId)
                .set(User::getCollegeId, college == null ? null : college.getId())
                .set(User::getCollege, college == null ? null : college.getName())
                .set(User::getMajorId, major == null ? null : major.getId())
                .set(User::getMajor, major == null ? null : major.getName())
                .set(User::getEntryYear, request.entryYear())
                .set(User::getGrade, GrowthTerms.gradeLabel(term))
                .set(User::getUpdatedAt, now));
        return userService.profile(userId);
    }
}

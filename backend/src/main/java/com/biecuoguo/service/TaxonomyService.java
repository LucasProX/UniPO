package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.Category;
import com.biecuoguo.domain.College;
import com.biecuoguo.domain.Major;
import com.biecuoguo.domain.School;
import com.biecuoguo.domain.Tag;
import com.biecuoguo.dto.OptionDtos;
import com.biecuoguo.dto.NoticeDtos;
import com.biecuoguo.mapper.CategoryMapper;
import com.biecuoguo.mapper.CollegeMapper;
import com.biecuoguo.mapper.MajorMapper;
import com.biecuoguo.mapper.SchoolMapper;
import com.biecuoguo.mapper.TagMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TaxonomyService {
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final SchoolMapper schoolMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;

    public TaxonomyService(CategoryMapper categoryMapper, TagMapper tagMapper, SchoolMapper schoolMapper, CollegeMapper collegeMapper, MajorMapper majorMapper) {
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.schoolMapper = schoolMapper;
        this.collegeMapper = collegeMapper;
        this.majorMapper = majorMapper;
    }

    public List<NoticeDtos.CategoryView> categories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>().orderByAsc(Category::getSortOrder))
                .stream()
                .map(NoticeDtos.CategoryView::from)
                .toList();
    }

    public List<NoticeDtos.TagView> tags() {
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>().orderByAsc(Tag::getType).orderByAsc(Tag::getId))
                .stream()
                .map(NoticeDtos.TagView::from)
                .toList();
    }

    public List<School> schools() {
        return schoolMapper.selectList(new LambdaQueryWrapper<School>().eq(School::getStatus, "active"));
    }

    public List<OptionDtos.SchoolOption> schoolOptions() {
        return schools().stream()
                .map(school -> new OptionDtos.SchoolOption(school.getId(), school.getName(), school.getCode(), school.getCity()))
                .toList();
    }

    public List<OptionDtos.CollegeOption> collegeOptions(Long schoolId) {
        LambdaQueryWrapper<College> wrapper = new LambdaQueryWrapper<College>().eq(College::getStatus, "active");
        if (schoolId != null) {
            wrapper.eq(College::getSchoolId, schoolId);
        }
        return collegeMapper.selectList(wrapper.orderByAsc(College::getId)).stream()
                .map(college -> new OptionDtos.CollegeOption(college.getId(), college.getSchoolId(), college.getName(), college.getCode()))
                .toList();
    }

    public List<OptionDtos.MajorOption> majorOptions(Long schoolId, Long collegeId) {
        LambdaQueryWrapper<Major> wrapper = new LambdaQueryWrapper<Major>().eq(Major::getStatus, "active");
        if (schoolId != null) {
            wrapper.eq(Major::getSchoolId, schoolId);
        }
        if (collegeId != null) {
            wrapper.eq(Major::getCollegeId, collegeId);
        }
        return majorMapper.selectList(wrapper.orderByAsc(Major::getId)).stream()
                .map(major -> new OptionDtos.MajorOption(major.getId(), major.getSchoolId(), major.getCollegeId(), major.getName(), major.getCode()))
                .toList();
    }

    public List<OptionDtos.GradeOption> grades() {
        return List.of(
                new OptionDtos.GradeOption("freshman", "大一"),
                new OptionDtos.GradeOption("sophomore", "大二"),
                new OptionDtos.GradeOption("junior", "大三"),
                new OptionDtos.GradeOption("senior", "大四"),
                new OptionDtos.GradeOption("graduate", "研究生"),
                new OptionDtos.GradeOption("operator", "运营")
        );
    }

    public Map<Long, Category> categoryMap() {
        return categoryMapper.selectList(null).stream().collect(Collectors.toMap(Category::getId, c -> c));
    }

    public Map<Long, School> schoolMap() {
        return schoolMapper.selectList(null).stream().collect(Collectors.toMap(School::getId, s -> s));
    }

    public Map<Long, Tag> tagMap() {
        return tagMapper.selectList(null).stream().collect(Collectors.toMap(Tag::getId, t -> t));
    }
}

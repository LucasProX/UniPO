package com.biecuoguo.web;

import com.biecuoguo.domain.School;
import com.biecuoguo.dto.NoticeDtos;
import com.biecuoguo.dto.OptionDtos;
import com.biecuoguo.service.TaxonomyService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TaxonomyController {
    private final TaxonomyService taxonomyService;

    public TaxonomyController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @GetMapping("/api/categories")
    public ApiResponse<List<NoticeDtos.CategoryView>> categories() {
        return ApiResponse.ok(taxonomyService.categories());
    }

    @GetMapping("/api/tags")
    public ApiResponse<List<NoticeDtos.TagView>> tags() {
        return ApiResponse.ok(taxonomyService.tags());
    }

    @GetMapping("/api/schools")
    public ApiResponse<List<School>> schools() {
        return ApiResponse.ok(taxonomyService.schools());
    }

    @GetMapping("/api/options/schools")
    public ApiResponse<List<OptionDtos.SchoolOption>> schoolOptions() {
        return ApiResponse.ok(taxonomyService.schoolOptions());
    }

    @GetMapping("/api/options/colleges")
    public ApiResponse<List<OptionDtos.CollegeOption>> collegeOptions(@RequestParam(required = false) Long schoolId) {
        return ApiResponse.ok(taxonomyService.collegeOptions(schoolId));
    }

    @GetMapping("/api/options/majors")
    public ApiResponse<List<OptionDtos.MajorOption>> majorOptions(@RequestParam(required = false) Long schoolId, @RequestParam(required = false) Long collegeId) {
        return ApiResponse.ok(taxonomyService.majorOptions(schoolId, collegeId));
    }

    @GetMapping("/api/options/grades")
    public ApiResponse<List<OptionDtos.GradeOption>> grades() {
        return ApiResponse.ok(taxonomyService.grades());
    }
}

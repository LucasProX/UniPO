package com.biecuoguo.dto;

public final class OptionDtos {
    private OptionDtos() {}

    public record SchoolOption(Long id, String name, String code, String city) {}
    public record CollegeOption(Long id, Long schoolId, String name, String code) {}
    public record MajorOption(Long id, Long schoolId, Long collegeId, String name, String code) {}
    public record GradeOption(String code, String name) {}
}

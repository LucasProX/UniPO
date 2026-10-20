package com.biecuoguo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@MapperScan({"com.biecuoguo.mapper", "com.biecuoguo.media.internal", "com.biecuoguo.message.internal",
        "com.biecuoguo.post.internal"})
@ConfigurationPropertiesScan
public class BieCuoGuoApplication {
    public static void main(String[] args) {
        SpringApplication.run(BieCuoGuoApplication.class, args);
    }
}

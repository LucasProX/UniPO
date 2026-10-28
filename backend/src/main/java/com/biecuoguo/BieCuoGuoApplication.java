package com.biecuoguo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan({"com.biecuoguo.mapper", "com.biecuoguo.activity.internal", "com.biecuoguo.wallet.internal", "com.biecuoguo.notification.internal", "com.biecuoguo.media.internal", "com.biecuoguo.message.internal",
        "com.biecuoguo.post.internal", "com.biecuoguo.spotlight.internal", "com.biecuoguo.push.internal",
        "com.biecuoguo.admin.internal", "com.biecuoguo.achievement.internal", "com.biecuoguo.auth.internal", "com.biecuoguo.growth.internal", "com.biecuoguo.feedback.internal", "com.biecuoguo.permission.internal", "com.biecuoguo.report.internal"})
@ConfigurationPropertiesScan
@EnableScheduling
public class BieCuoGuoApplication {
    public static void main(String[] args) {
        SpringApplication.run(BieCuoGuoApplication.class, args);
    }
}

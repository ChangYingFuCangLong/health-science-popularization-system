package com.healthscience.rag.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * 全局时间格式：接口文档 §1 约定 {@code yyyy-MM-dd HH:mm:ss}。
 *
 * <p><b>为什么不只用 {@code spring.jackson.local-date-time-format}</b>：
 * 实测在 Spring Boot 3.5 上该属性对 {@code LocalDateTime} 不生效，
 * 输出仍是 ISO 格式（{@code 2026-09-18T12:58:49}）。
 * 直接的序列化器注册不受属性绑定影响，行为确定。
 */
@Configuration
public class JacksonConfig {

    /** 与接口文档 §1 一致 */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        return builder -> builder
                .simpleDateFormat(DATE_TIME_PATTERN)
                .serializers(new LocalDateTimeSerializer(formatter))
                .deserializers(new LocalDateTimeDeserializer(formatter));
    }
}

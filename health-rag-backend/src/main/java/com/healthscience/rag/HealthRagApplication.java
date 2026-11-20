package com.healthscience.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 健康科普 RAG 系统 · 启动类。
 *
 * <p>项目：基于检索增强生成（RAG）的健康科普系统的设计与实现
 * <p>设计基线见 {@code docs/V2/14-后端说明文档.md}，本模块为后端服务（设计文档中的 {@code backend/} 即本目录）。
 */
@SpringBootApplication
@MapperScan("com.healthscience.rag.mapper")
public class HealthRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthRagApplication.class, args);
    }
}

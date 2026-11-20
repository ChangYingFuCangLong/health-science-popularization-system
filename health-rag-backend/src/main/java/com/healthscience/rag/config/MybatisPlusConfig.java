package com.healthscience.rag.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>注意：分页插件自 3.5.9 起已拆分到 {@code mybatis-plus-jsqlparser} 模块，
 * 需在 pom 中单独声明，否则分页会静默失效（返回全量数据）。
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数，与接口文档「pageSize 最大 100」的约定一致 */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        // 兜住前端传入超大 pageSize 的情况
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}

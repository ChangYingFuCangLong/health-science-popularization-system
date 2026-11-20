package com.healthscience.rag.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi 配置（任务卡 B-7）。
 *
 * <p>Swagger UI：{@code /swagger-ui.html}；机器可读契约：{@code /v3/api-docs}。
 * 接口字段以 {@code docs/V2/02-接口文档.md} 为契约唯一来源，注解仅用于保证
 * 在线文档与代码不漂移；两者冲突时先改 02 再改代码。
 *
 * <p>配置 bearer-jwt 安全方案后，Swagger UI 右上角 Authorize 按钮可直接粘贴 token 调试。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI healthRagOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("健康科普 RAG 系统 API")
                        .description("基于检索增强生成（RAG）的健康科普系统 · 后端接口。"
                                + "契约唯一来源：docs/V2/02-接口文档.md")
                        .version("V2 / M9"))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}

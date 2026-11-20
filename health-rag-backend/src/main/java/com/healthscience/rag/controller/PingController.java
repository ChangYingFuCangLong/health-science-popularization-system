package com.healthscience.rag.controller;

import com.healthscience.rag.common.result.R;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口。
 *
 * <p>免认证：用于启动自检与前后端连通性验证（M1 完成标志之一）。
 */
@Tag(name = "健康检查")
@RestController
@RequestMapping("/api")
public class PingController {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/ping")
    public R<Map<String, Object>> ping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("app", "health-rag-backend");
        data.put("time", LocalDateTime.now().format(TIME_FORMAT));
        // javaVersion 已移除（测试报告 低-2：免认证接口不暴露运行时版本）
        return R.ok(data);
    }
}

package com.healthscience.rag.runner;

import com.healthscience.rag.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 文档状态机僵尸记录重置（M12/B-19，V1 失败模式表承诺项）。
 *
 * <p>上传流水线在后台线程执行，服务重启会把 status=1（处理中）的文档
 * 永远卡住 —— 启动时扫描并置为失败态，管理端可见 failReason，用户重新上传即可。
 * 晚于向量预热（Order 10），早于语料导入（默认最低优先级）。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class DocumentStatusResetRunner implements ApplicationRunner {

    private final KbDocumentMapper documentMapper;

    @Override
    public void run(ApplicationArguments args) {
        int n = documentMapper.resetParsingZombies();
        if (n > 0) {
            log.warn("[DocumentStatus] 重启发现 {} 条处理中文档（僵尸记录），已置为失败态", n);
        }
    }
}

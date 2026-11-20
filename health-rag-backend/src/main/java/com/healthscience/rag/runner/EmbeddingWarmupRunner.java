package com.healthscience.rag.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.healthscience.rag.ai.EmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动即预热 embedding 模型。
 *
 * <p><b>为什么需要</b>：本地 bge-m3 首次调用要先加载模型，实测这 7 秒会<b>全额计入
 * 用户请求的首字延迟</b>（首次问答 firstTokenMs≈7000，热态≈3200，NFR 要求 ≤3000）。
 * 放在启动阶段付掉这个代价，用户第一次提问就是热态。
 *
 * <p>代价是应用启动慢几秒 —— 对"启动时多等、使用时不卡"而言是划算的交换。
 */
@Slf4j
@Component
@Order(10)   // 早于 KbImportRunner（默认为最低优先级），先预热再导入
@RequiredArgsConstructor
public class EmbeddingWarmupRunner implements ApplicationRunner {

    private final EmbeddingService embeddingService;

    @Value("${health.rag.warmup-enabled:true}")
    private boolean warmupEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!warmupEnabled) {
            log.info("[Warmup] 已按配置跳过 embedding 预热");
            return;
        }
        long startAt = System.currentTimeMillis();
        try {
            embeddingService.embed("预热");
            log.info("[Warmup] embedding 模型预热完成，耗时 {}ms", System.currentTimeMillis() - startAt);
        } catch (Exception e) {
            // 预热失败不能阻止应用启动：模型服务可能稍后才就绪，
            // 由 RagChatService 的降级逻辑兜底（检索失败降级为无引用回答）
            log.warn("[Warmup] 预热失败，不影响启动（首次请求可能偏慢）: {}", e.getMessage());
        }
    }
}

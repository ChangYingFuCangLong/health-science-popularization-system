package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量化能力实现 · 云端百炼 text-embedding-v4（1024 维，需额度）。
 *
 * <p>包装 Spring AI 的 {@code openAiEmbeddingModel}（OpenAI 兼容模式）。
 * ⚠️ 与本地 bge-m3 的向量空间不通用：切换 provider 后必须全量重建向量（ADR-023 维持）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.rag.embedding-provider", havingValue = "openai")
public class DashScopeEmbeddingService implements EmbeddingService {

    private final EmbeddingModel delegate;
    private final String modelName;
    private volatile Integer cachedDimension;

    public DashScopeEmbeddingService(
            @Qualifier("openAiEmbeddingModel") EmbeddingModel delegate,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-v4}") String modelName) {
        this.delegate = delegate;
        this.modelName = modelName;
        log.info("[Embedding] 云端百炼向量模型: {}", modelName);
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return delegate.embed(texts);
    }

    @Override
    public int dimension() {
        Integer d = cachedDimension;
        if (d == null) {
            synchronized (this) {
                if (cachedDimension == null) {
                    cachedDimension = delegate.dimensions();
                }
                d = cachedDimension;
            }
        }
        return d;
    }

    @Override
    public String modelName() {
        return modelName;
    }
}

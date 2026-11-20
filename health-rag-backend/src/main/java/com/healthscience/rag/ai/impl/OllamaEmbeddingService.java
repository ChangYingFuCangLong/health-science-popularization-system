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
 * 向量化能力实现 · 本地 Ollama bge-m3（1024 维，免费离线）。
 *
 * <p>包装 Spring AI 的 {@code ollamaEmbeddingModel}（依赖收敛见 C1/C2：
 * 本类是 EmbeddingService 的唯一实现之一，调用方不再接触框架类型）。
 * 与 {@link DashScopeEmbeddingService} 按 {@code health.rag.embedding-provider} 互斥装配。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.rag.embedding-provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaEmbeddingService implements EmbeddingService {

    private final EmbeddingModel delegate;
    private final String modelName;
    private volatile Integer cachedDimension;

    public OllamaEmbeddingService(
            @Qualifier("ollamaEmbeddingModel") EmbeddingModel delegate,
            @Value("${spring.ai.ollama.embedding.options.model:bge-m3}") String modelName) {
        this.delegate = delegate;
        this.modelName = modelName;
        log.info("[Embedding] 本地 Ollama 向量模型: {}", modelName);
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
        // dimensions() 首次调用会真实 embed 一条样例（约百毫秒），缓存住
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

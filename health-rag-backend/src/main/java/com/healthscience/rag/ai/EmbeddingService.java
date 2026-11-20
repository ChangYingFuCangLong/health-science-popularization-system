package com.healthscience.rag.ai;

import java.util.List;

/**
 * 向量化能力接口（框架无关，见架构约束 C1/C2）。
 *
 * <p><b>实现演进</b>：开发期用 bge-m3（Ollama 本地，1024 维）；上线期切云端 embedding API
 * —— 同模型同维度则向量可复用，<b>换模型必须全量重建向量</b>（向量空间不同，混用不报错但结果全错）。
 */
public interface EmbeddingService {

    /** 单条向量化 */
    float[] embed(String text);

    /** 批量向量化，用于语料入库 */
    List<float[]> embedBatch(List<String> texts);

    /** 向量维度，用于与 {@code kb_vector_meta} 记录值比对（防静默失败） */
    int dimension();

    /** 模型标识，如 {@code BAAI/bge-m3} */
    String modelName();
}

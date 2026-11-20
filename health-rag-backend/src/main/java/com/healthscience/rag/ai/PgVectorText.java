package com.healthscience.rag.ai;

/**
 * pgvector 文本字面量工具（ADR-023）。
 *
 * <p>pgvector 的 JDBC 参数以 1024 维文本形式传递（{@code [v1,v2,...]}），
 * SQL 侧配合 {@code CAST(? AS vector)} 完成解析。文本精度用 float 原生
 * {@code toString}（约 7 位有效数字），对余弦相似度的影响可忽略。
 */
public final class PgVectorText {

    private PgVectorText() {
    }

    public static String of(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}

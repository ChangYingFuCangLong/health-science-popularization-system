package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.EvalResult;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 实验结果 Mapper。
 */
public interface EvalResultMapper extends BaseMapper<EvalResult> {

    /** §7.5 汇总里需要的最新批次号（避免前端必须手动传 batchNo） */
    @Select("""
            SELECT batch_no FROM eval_result
             WHERE deleted = FALSE
             GROUP BY batch_no
             ORDER BY MAX(create_time) DESC
             LIMIT 1
            """)
    String selectLatestBatchNo();

    /** 全部批次，供前端下拉选择 */
    @Select("""
            SELECT batch_no AS "batchNo", COUNT(*) AS count, MAX(create_time) AS "lastAt"
              FROM eval_result
             WHERE deleted = FALSE
             GROUP BY batch_no
             ORDER BY lastAt DESC
            """)
    List<Map<String, Object>> selectBatches();
}

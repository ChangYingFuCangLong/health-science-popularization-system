package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.MetricDef;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 指标目录 Mapper。
 */
public interface MetricDefMapper extends BaseMapper<MetricDef> {

    /** 全部可用目录（标准 + 指定用户的自定义） */
    @Select("""
            SELECT * FROM metric_def
             WHERE deleted = FALSE AND (owner_user_id IS NULL OR owner_user_id = #{userId})
             ORDER BY owner_user_id NULLS FIRST, id
            """)
    List<MetricDef> selectCatalog(@Param("userId") Long userId);
}

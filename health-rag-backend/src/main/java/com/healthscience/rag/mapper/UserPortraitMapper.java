package com.healthscience.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.healthscience.rag.entity.UserPortrait;

/**
 * 健康画像 Mapper（单用户单行，UPSERT 语义在应用层）。
 */
public interface UserPortraitMapper extends BaseMapper<UserPortrait> {
}

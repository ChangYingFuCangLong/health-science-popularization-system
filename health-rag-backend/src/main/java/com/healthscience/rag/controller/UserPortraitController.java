package com.healthscience.rag.controller;

import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.model.dto.UserPortraitDTO;
import com.healthscience.rag.service.UserPortraitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康画像接口（M16，契约 08 §5，#53–#56）。
 *
 * <p>登录用户可用，操作目标一律取当前登录用户。画像内容的隐私红线见
 * {@link com.healthscience.rag.entity.UserPortrait}。
 */
@Tag(name = "健康画像 /api/health/portrait")
@RestController
@RequestMapping("/api/health/portrait")
@RequiredArgsConstructor
public class UserPortraitController {

    private final UserPortraitService userPortraitService;

    /** #53 读取画像（未填写 → exists=false，非 404） */
    @GetMapping
    @Operation(summary = "读取画像（含完善度）")
    public R<UserPortraitDTO.PortraitVO> get() {
        return R.ok(userPortraitService.get(UserContext.requireId()));
    }

    /** #54 保存画像（全量提交，UPSERT） */
    @PutMapping
    @Operation(summary = "保存画像")
    public R<Void> save(@Valid @RequestBody UserPortraitDTO.SaveRequest request) {
        userPortraitService.save(UserContext.requireId(), request);
        return R.ok();
    }

    /** #55 清空画像（物理删除；专属对话保留但不再注入，后果前端提示） */
    @DeleteMapping
    @Operation(summary = "清空画像")
    public R<Void> delete() {
        userPortraitService.delete(UserContext.requireId());
        return R.ok();
    }

    /** #56 字段字典（枚举与中文标签的唯一来源） */
    @GetMapping("/options")
    @Operation(summary = "字段字典")
    public R<UserPortraitDTO.OptionsVO> options() {
        return R.ok(userPortraitService.options());
    }
}

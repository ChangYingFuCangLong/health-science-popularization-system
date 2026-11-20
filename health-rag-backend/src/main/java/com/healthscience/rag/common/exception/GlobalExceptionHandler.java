package com.healthscience.rag.common.exception;

import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把各类异常统一转换为 {@link R} 结构，保证前端只需处理一种响应格式。
 *
 * <p>错误码取值一律来自 {@link ErrorCode}，与接口文档 §1.3 对齐。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：预期内的分支，记 warn 即可，不打堆栈 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e, HttpServletRequest request) {
        log.warn("[业务异常] {} {} -> code={}, message={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验失败。
     *
     * <p>覆盖 {@code MethodArgumentNotValidException}（@RequestBody 校验）
     * 与 {@code BindException}（表单绑定校验）—— 前者继承自后者。
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleValidation(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        log.warn("[参数校验失败] {}", message);
        return R.fail(ErrorCode.BAD_REQUEST, message);
    }

    /** 请求体不可读、缺少必填参数、参数类型不匹配 */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public R<Void> handleBadRequest(Exception e) {
        log.warn("[请求参数异常] {}", e.getMessage());
        return R.fail(ErrorCode.BAD_REQUEST, "请求参数有误");
    }

    /** 路径不存在 */
    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        return R.fail(ErrorCode.NOT_FOUND, "资源不存在");
    }

    /** 缺少 multipart 字段（测试报告 P3-4：/api/chat/asr 缺 audio 原为 500） */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public R<Void> handleMissingPart(org.springframework.web.multipart.support.MissingServletRequestPartException e) {
        log.warn("[参数缺失] multipart 字段: {}", e.getRequestPartName());
        return R.fail(ErrorCode.BAD_REQUEST, "缺少必要的上传字段: " + e.getRequestPartName());
    }

    /**
     * multipart 请求本身不合法（非 multipart/坏 body/超限家族）——
     * 复测报告 P3-4 确认 500 的真正来源：第一轮只注册了 MissingServletRequestPart，
     * 漏了这个家族。两个子类语义不同，分开给文案。
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUpload(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("[上传超限] {}", e.getMessage());
        return R.fail(ErrorCode.BAD_REQUEST, "上传文件超过大小限制");
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public R<Void> handleMultipart(org.springframework.web.multipart.MultipartException e) {
        log.warn("[multipart 异常] {}", e.getMessage());
        return R.fail(ErrorCode.BAD_REQUEST, "请使用 multipart/form-data 提交并包含必要字段");
    }

    /**
     * Content-Type 与 consumes 不匹配（如向 /asr 发 application/json）。
     * 不加此 handler 会被兜底 Exception 捕获成 500——Spring 本会翻译为 415。
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public R<Void> handleMediaType(org.springframework.web.HttpMediaTypeNotSupportedException e) {
        log.warn("[Content-Type 不匹配] {}", e.getMessage());
        return R.fail(ErrorCode.BAD_REQUEST, "请求 Content-Type 不正确（该接口要求 multipart/form-data）");
    }

    /** 请求方法不匹配（GET 打到 POST 端点等），同理从兜底 500 中救回 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public R<Void> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("[方法不支持] {}", e.getMessage());
        return R.fail(ErrorCode.BAD_REQUEST, "不支持的请求方法: " + e.getMethod());
    }

    /** 兜底：未预期的异常必须完整记录堆栈，否则线上无从排查 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e, HttpServletRequest request) {
        log.error("[未处理异常] {} {}", request.getMethod(), request.getRequestURI(), e);
        return R.fail(ErrorCode.INTERNAL_ERROR, "服务器内部错误");
    }
}

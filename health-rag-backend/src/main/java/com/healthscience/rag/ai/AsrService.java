package com.healthscience.rag.ai;

/**
 * 语音识别能力接口（框架无关，任务卡 B-18，契约 02 §4.8）。
 *
 * <p><b>隐私红线</b>：音频只在内存中传递，<b>不落盘</b>，识别完成即弃；
 * 只有用户确认发送后的文本才进 {@code chat_message}。
 */
public interface AsrService {

    /**
     * 转写一段音频。
     *
     * @param audio       音频字节（webm/opus 或 wav 等，≤15MB、≤60s，由 Controller 校验）
     * @param contentType MIME 主类型（如 {@code audio/webm}）
     * @return 转写文本；无有效语音时为空串
     * @throws com.healthscience.rag.common.exception.BizException 识别失败（错误码 2005）
     */
    AsrResult transcribe(byte[] audio, String contentType);

    /**
     * @param audioSeconds 音频时长（秒，来自引擎返回），契约 durationMs 由此换算
     */
    record AsrResult(String text, int audioSeconds) {
    }
}

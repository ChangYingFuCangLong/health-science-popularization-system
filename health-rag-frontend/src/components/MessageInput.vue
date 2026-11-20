<script setup lang="ts">
/* ============================================================
 * 输入区 v2.2
 *   Enter 发送 / Shift+Enter 换行（含中文输入法保护，沿用 v1）
 *   语音输入：Web Speech API（能力探测，不支持则不渲染按钮）。
 *   02 v4.0 已移除前端的云端 ASR 入口（按住说话 / useAsr 已删），语音输入仅此一条路径。
 *   注意：后端 POST /api/chat/asr 与错误码 2005 仍保留可用，只是前端不再调用。
 *   「自动朗读」开关：回答完成后系统 TTS 朗读（触发在对话页）。
 * ============================================================ */

import { nextTick, onMounted, ref, watch } from 'vue'
import { useSpeech } from '@/composer/useSpeech'
import { useAutoRead } from '@/composer/useAutoRead'

const props = defineProps<{ streaming: boolean; disabled?: boolean }>()
const emit = defineEmits<{ send: [text: string]; stop: [] }>()

const draft = ref('')
const textarea = ref<HTMLTextAreaElement | null>(null)

/** 自动长高：2 行起步，最多 ~6 行后内部滚动（长问题不再闷在固定框里） */
function resize(): void {
  const el = textarea.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

watch(draft, () => void nextTick(resize))
onMounted(resize)

const { recognitionSupported, listening, startListening, stopListening } = useSpeech()
const autoRead = useAutoRead()

function submit(): void {
  const text = draft.value.trim()
  if (!text || props.streaming) return
  emit('send', text)
  draft.value = ''
  stopListening()
}

function focus(): void {
  textarea.value?.focus()
}

/** 语音识别结果回填草稿，不自动发送 */
function onVoiceText(text: string): void {
  draft.value = draft.value ? `${draft.value.replace(/\s*$/, '')} ${text}` : text
  textarea.value?.focus()
}

function toggleVoice(): void {
  if (listening.value) {
    stopListening()
  } else {
    startListening(onVoiceText)
  }
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

function setDraft(text: string): void {
  draft.value = text
}

defineExpose({ focus, setDraft })
</script>

<template>
  <div class="glass border-t border-line px-4 pb-2 pt-3">
    <div class="mx-auto max-w-chat">
      <div
        class="rounded-input border border-line bg-surface px-3 py-2 transition-colors focus-within:border-brand"
      >
        <textarea
          ref="textarea"
          v-model="draft"
          rows="2"
          :disabled="disabled"
          :placeholder="
            streaming
              ? '正在生成回答…'
              : listening
                ? '正在聆听，请讲话…'
                : '描述你的健康问题，Enter 发送 / Shift+Enter 换行'
          "
          class="block max-h-40 w-full resize-none overflow-y-auto bg-transparent text-body text-ink outline-none placeholder:text-ink-muted"
          @keydown="onKeydown"
        />

        <div class="flex items-center justify-end gap-2 pt-1">
          <span v-if="draft.length > 0" class="tnum mr-auto text-hint text-ink-muted">
            {{ draft.length }}
          </span>

          <!-- 自动朗读开关（F-5：输出走系统 TTS，零后端） -->
          <button
            v-if="!streaming"
            type="button"
            class="flex h-11 w-11 items-center justify-center rounded-full border transition-colors md:h-8 md:w-8"
            :class="
              autoRead.enabled.value
                ? 'border-brand text-brand'
                : 'border-line text-ink-muted hover:border-brand hover:text-brand'
            "
            :aria-pressed="autoRead.enabled.value"
            :aria-label="autoRead.enabled.value ? '关闭自动朗读' : '开启自动朗读'"
            title="回答完成后自动朗读"
            @click="autoRead.toggle()"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M11 5 6 9H2v6h4l5 4V5z" />
              <path v-if="autoRead.enabled.value" d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13" />
              <path v-else d="M22 9l-6 6M16 9l6 6" />
            </svg>
          </button>

          <!-- 语音输入（Web Speech，能力探测；云端 ASR 前端入口已移除，后端保留） -->
          <button
            v-if="recognitionSupported && !streaming"
            type="button"
            class="flex h-11 w-11 items-center justify-center rounded-full border border-line text-ink-muted transition-colors hover:border-brand hover:text-brand md:h-8 md:w-8"
            :class="listening ? 'border-accent text-accent voice-live' : ''"
            :aria-label="listening ? '停止语音输入' : '语音输入'"
            :aria-pressed="listening"
            title="语音输入"
            @click="toggleVoice"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="9" y="2" width="6" height="12" rx="3" />
              <path d="M5 10a7 7 0 0 0 14 0M12 19v3" />
            </svg>
          </button>

          <button
            v-if="streaming"
            type="button"
            class="min-h-11 rounded-btn border border-line bg-surface-2 px-5 text-body text-ink transition-transform hover:scale-[1.02] md:min-h-0 md:px-4 md:py-1.5"
            @click="emit('stop')"
          >
            停止
          </button>
          <button
            v-else
            type="button"
            :disabled="!draft.trim() || disabled"
            class="min-h-11 rounded-btn grad-primary px-5 text-body transition-transform hover:scale-[1.02] active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:scale-100 md:min-h-0 md:px-4 md:py-1.5"
            @click="submit"
          >
            发送
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.voice-live {
  animation: voice-breathe 1.1s ease-in-out infinite;
}
@keyframes voice-breathe {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(251, 146, 60, 0.35);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(251, 146, 60, 0);
  }
}
</style>

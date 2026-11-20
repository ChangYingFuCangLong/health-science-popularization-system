/* ============================================================
 * 语音输入 / 朗读（Web Speech API，浏览器原生，零后端改动）
 *
 * 能力探测：SpeechRecognition 目前 Chromium 系支持（前缀 webkit），
 * 不支持的浏览器（Firefox / Safari 旧版）直接不渲染对应按钮，
 * 而不是渲染一个点了报错的按钮。
 *
 * 朗读前剥掉 Markdown 记号：屏幕朗读的是内容，不是标记语言。
 * ============================================================ */

import { ref } from 'vue'

interface SpeechRecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  start(): void
  stop(): void
  onresult: ((e: SpeechRecognitionEventLike) => void) | null
  onerror: (() => void) | null
  onend: (() => void) | null
}

interface SpeechRecognitionEventLike {
  resultIndex: number
  results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }>
}

type SpeechRecognitionCtor = new () => SpeechRecognitionLike

function getRecognitionCtor(): SpeechRecognitionCtor | null {
  const w = window as unknown as {
    SpeechRecognition?: SpeechRecognitionCtor
    webkitSpeechRecognition?: SpeechRecognitionCtor
  }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

/** 剥掉 Markdown 记号，朗读纯文本 */
export function stripMarkdown(md: string): string {
  return md
    .replace(/```[\s\S]*?```/g, '，代码块省略，')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/[*_~]{1,3}([^*_~]+)[*_~]{1,3}/g, '$1')
    .replace(/^\s*[-+>]\s+/gm, '')
    .replace(/\|/g, '，')
    .replace(/\n+/g, '，')
    .trim()
}

/* TTS 朗读状态为模块级单例：自动朗读（ChatView 触发）与消息卡「停止朗读」
 * 按钮共享同一份状态，避免多实例各自为政。 */
const speaking = ref(false)

export function useSpeech() {
  const Ctor = getRecognitionCtor()

  /* ---------------- 语音输入 ---------------- */
  const listening = ref(false)
  let recognition: SpeechRecognitionLike | null = null

  function startListening(onText: (text: string) => void): void {
    if (!Ctor || listening.value) return
    recognition = new Ctor()
    recognition.lang = 'zh-CN'
    recognition.continuous = false
    recognition.interimResults = true

    recognition.onresult = (e) => {
      let text = ''
      for (let i = e.resultIndex; i < e.results.length; i++) {
        text += e.results[i][0]?.transcript ?? ''
      }
      if (text.trim()) onText(text.trim())
    }
    recognition.onerror = () => {
      listening.value = false
    }
    recognition.onend = () => {
      listening.value = false
    }

    listening.value = true
    recognition.start()
  }

  function stopListening(): void {
    recognition?.stop()
    listening.value = false
  }

  /* ---------------- 朗读回答（TTS） ---------------- */

  function speak(text: string): void {
    if (!('speechSynthesis' in window) || !text.trim()) return
    window.speechSynthesis.cancel()
    const utterance = new SpeechSynthesisUtterance(stripMarkdown(text))
    utterance.lang = 'zh-CN'
    utterance.rate = 1
    utterance.onend = () => {
      speaking.value = false
    }
    utterance.onerror = () => {
      speaking.value = false
    }
    speaking.value = true
    window.speechSynthesis.speak(utterance)
  }

  function stopSpeaking(): void {
    if ('speechSynthesis' in window) window.speechSynthesis.cancel()
    speaking.value = false
  }

  return {
    recognitionSupported: Ctor != null,
    synthesisSupported: typeof window !== 'undefined' && 'speechSynthesis' in window,
    listening,
    speaking,
    startListening,
    stopListening,
    speak,
    stopSpeaking
  }
}

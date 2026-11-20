<script setup lang="ts">
/* ============================================================
 * 健康画像 · 编辑面板（docs/V2/08 §6.4）
 *
 * 三条设计决定：
 *   1. **全部字段可选**。逼着用户填完才能保存，结果是用户直接关掉面板。
 *      标注"填写后可获得更贴合的回答"，把选择权留给用户。
 *   2. **选项标签来自服务端字典**（#56）。前端硬编码中文，等于在客户端埋了
 *      一份会与服务端漂移的副本——加一个慢病标签就要改前端。
 *   3. **知情同意前置**。画像会随提问发送给第三方模型服务商，这必须先说清楚、
 *      拿到明确同意，再开始收集。放在表单之后或藏在条款里，都不算知情。
 *
 * 外壳复用 SideSheet；本组件只管表单与校验。
 * ============================================================ */

import { computed, ref, watch } from 'vue'
import * as portraitApi from '@/api/portrait'
import { emptyPortrait, validatePortraitField } from '@/api/portrait'
import type { Portrait, PortraitInput, PortraitOptions } from '@/api/portrait'
import SideSheet from '@/components/SideSheet.vue'
import { showMessage } from '@/utils/message'

const props = defineProps<{
  open: boolean
  portrait: Portrait | null
  options: PortraitOptions | null
  /** 尚未同意过数据使用说明 → 先展示同意步骤 */
  needConsent: boolean
  saving?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved'): void
  (e: 'cleared'): void
  (e: 'consent'): void
}>()

/* ---------------- 步骤 ---------------- */

const step = ref<'consent' | 'form'>('form')

watch(
  () => props.open,
  (open) => {
    if (!open) return
    // 每次打开都从最新画像重建草稿，避免上次未保存的改动残留
    draft.value = props.portrait?.exists ? toInput(props.portrait) : emptyPortrait()
    errors.value = {}
    confirmClear.value = false
    step.value = props.needConsent ? 'consent' : 'form'
  },
  { immediate: true }
)

function toInput(p: Portrait): PortraitInput {
  return {
    gender: p.gender,
    birthYear: p.birthYear,
    heightCm: p.heightCm,
    weightKg: p.weightKg,
    conditions: [...p.conditions],
    allergies: p.allergies ?? '',
    medications: p.medications ?? '',
    exercise: p.exercise,
    sleepHours: p.sleepHours,
    smoking: p.smoking,
    drinking: p.drinking,
    diet: [...p.diet],
    goals: [...p.goals],
    note: p.note ?? ''
  }
}

/* ---------------- 表单状态 ---------------- */

const draft = ref<PortraitInput>(emptyPortrait())
const errors = ref<Record<string, string>>({})
const savingLocal = ref(false)
const confirmClear = ref(false)

const busy = computed(() => props.saving || savingLocal.value)

/** 数字输入：空串 → null（"没填"与"填了 0"必须能区分） */
function onNumber(field: 'birthYear' | 'heightCm' | 'weightKg' | 'sleepHours', e: Event): void {
  const raw = (e.target as HTMLInputElement).value.trim()
  const value = raw === '' ? null : Number(raw)
  draft.value[field] = value
  errors.value[field] = value == null ? '' : (validatePortraitField(field, value) ?? '')
}

/** 单选 chip：再点一次取消选择（画像字段全都可选，必须给"反悔"的出口） */
function toggleSingle<K extends 'gender' | 'exercise' | 'smoking' | 'drinking'>(
  field: K,
  value: string
): void {
  draft.value[field] = (draft.value[field] === value ? null : value) as PortraitInput[K]
}

/** 多选 chip */
function toggleMulti(field: 'conditions' | 'diet' | 'goals', value: string): void {
  const list = draft.value[field]
  const idx = list.indexOf(value)
  if (idx >= 0) list.splice(idx, 1)
  else list.push(value)
}

function isOn(field: 'gender' | 'exercise' | 'smoking' | 'drinking', value: string): boolean {
  return draft.value[field] === value
}

function hasError(): boolean {
  return Object.values(errors.value).some((v) => !!v)
}

/** 同意后才进入表单；这一步必须显式发生，不能默认已同意 */
function acceptConsent(): void {
  step.value = 'form'
  emit('consent')
}

/* ---------------- 提交 ---------------- */

async function save(): Promise<void> {
  if (hasError()) {
    showMessage('请先修正标红的内容', 'warning')
    return
  }
  savingLocal.value = true
  try {
    await portraitApi.savePortrait(draft.value)
    showMessage('健康画像已保存', 'success')
    emit('saved')
  } catch {
    /* 文案由拦截器统一提示 */
  } finally {
    savingLocal.value = false
  }
}

async function doClear(): Promise<void> {
  if (!confirmClear.value) {
    confirmClear.value = true
    window.setTimeout(() => (confirmClear.value = false), 3000)
    return
  }
  confirmClear.value = false
  savingLocal.value = true
  try {
    await portraitApi.clearPortrait()
    showMessage('已清空画像，专属对话的回答将不再参考它', 'info')
    emit('cleared')
  } catch {
    /* 拦截器已提示 */
  } finally {
    savingLocal.value = false
  }
}

const filledNow = computed(() => portraitApi.filledCountOf(draft.value))
const completenessNow = computed(() => portraitApi.completenessOf(draft.value))

/** chip 通用样式：选中态用品牌色描边 + 浅底，未选中为中性描边 */
function chipClass(on: boolean): string {
  return on
    ? 'border-brand bg-brand-soft text-brand'
    : 'border-line text-ink-muted hover:border-line-strong hover:text-ink'
}
</script>

<template>
  <SideSheet :open="open" label="健康画像编辑" width="520px" @close="emit('close')">
    <header class="flex shrink-0 items-start gap-3 border-b border-line px-4 py-3">
      <div class="min-w-0 flex-1">
        <h2 class="text-section font-medium text-ink">健康画像</h2>
        <p class="mt-0.5 text-hint text-ink-muted">
          <template v-if="step === 'form'">
            已填 {{ filledNow }} / {{ portraitApi.PORTRAIT_FIELD_TOTAL }} 项 · 填得越多，回答越贴合你
          </template>
          <template v-else>填写前请先了解两件事</template>
        </p>
      </div>
      <button
        type="button"
        class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-line text-ink-muted transition-colors hover:border-brand hover:text-brand max-md:h-11 max-md:w-11"
        aria-label="关闭画像编辑"
        @click="emit('close')"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </header>

    <!-- 步骤一：知情同意 -->
    <div v-if="step === 'consent'" class="min-h-0 flex-1 overflow-y-auto p-5">
      <ol class="space-y-4">
        <li class="flex gap-3">
          <span class="font-display tnum mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-line bg-surface-2 text-[11px] text-ink-muted">1</span>
          <p class="text-body leading-7 text-ink">
            你填写的这些信息，会<strong class="text-ink">随你的提问一起</strong>发送给模型服务商，
            用于生成针对性的回答。这是"个性化"能成立的前提，也是它唯一的代价。
          </p>
        </li>
        <li class="flex gap-3">
          <span class="font-display tnum mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-line bg-surface-2 text-[11px] text-ink-muted">2</span>
          <p class="text-body leading-7 text-ink">
            它<strong class="text-ink">只</strong>用于「专属对话」的回答依据：不参与知识库检索，
            不写入管理端可见的日志，其他用户也看不到。你可以随时在面板底部一键清空。
          </p>
        </li>
      </ol>
      <p class="mt-5 rounded-card border border-line bg-surface-2/50 p-3 text-hint text-ink-muted">
        画像不能替代医疗记录。请勿填入身份证号、病历号等与健康建议无关的敏感信息。
      </p>
      <div class="mt-6 flex flex-col gap-2">
        <button
          type="button"
          class="rounded-btn grad-primary py-2.5 text-body transition-transform hover:scale-[1.01] active:scale-[0.99] max-md:min-h-11"
          @click="acceptConsent"
        >
          我已了解，开始填写
        </button>
        <button
          type="button"
          class="rounded-btn border border-line py-2.5 text-body text-ink-muted transition-colors hover:text-ink max-md:min-h-11"
          @click="emit('close')"
        >
          暂时不用
        </button>
      </div>
    </div>

    <!-- 步骤二：表单 -->
    <template v-else>
      <div class="min-h-0 flex-1 space-y-6 overflow-y-auto p-4">
        <!-- 基本信息 -->
        <section>
          <h3 class="text-hint font-medium text-ink-muted">基本信息</h3>

          <p class="mt-2 text-hint text-ink-muted">性别</p>
          <div class="mt-1.5 flex flex-wrap gap-1.5">
            <button
              v-for="o in options?.genders ?? []"
              :key="o.value"
              type="button"
              class="rounded-full border px-3 py-1 text-body transition-colors max-md:min-h-10 max-md:px-4"
              :class="chipClass(isOn('gender', o.value))"
              @click="toggleSingle('gender', o.value)"
            >
              {{ o.label }}
            </button>
          </div>

          <div class="mt-3 grid grid-cols-3 gap-3">
            <label class="block">
              <span class="text-hint text-ink-muted">出生年</span>
              <input
                :value="draft.birthYear ?? ''"
                type="number"
                inputmode="numeric"
                placeholder="1998"
                class="mt-1 w-full rounded-input border bg-surface px-3 py-2 text-body text-ink outline-none focus:border-brand"
                :class="errors.birthYear ? 'border-danger' : 'border-line'"
                @input="onNumber('birthYear', $event)"
              />
            </label>
            <label class="block">
              <span class="text-hint text-ink-muted">身高 cm</span>
              <input
                :value="draft.heightCm ?? ''"
                type="number"
                inputmode="numeric"
                placeholder="175"
                class="mt-1 w-full rounded-input border bg-surface px-3 py-2 text-body text-ink outline-none focus:border-brand"
                :class="errors.heightCm ? 'border-danger' : 'border-line'"
                @input="onNumber('heightCm', $event)"
              />
            </label>
            <label class="block">
              <span class="text-hint text-ink-muted">体重 kg</span>
              <input
                :value="draft.weightKg ?? ''"
                type="number"
                inputmode="decimal"
                placeholder="65.5"
                class="mt-1 w-full rounded-input border bg-surface px-3 py-2 text-body text-ink outline-none focus:border-brand"
                :class="errors.weightKg ? 'border-danger' : 'border-line'"
                @input="onNumber('weightKg', $event)"
              />
            </label>
          </div>
          <p
            v-if="errors.birthYear || errors.heightCm || errors.weightKg"
            class="mt-1.5 text-hint text-danger"
          >
            {{ errors.birthYear || errors.heightCm || errors.weightKg }}
          </p>
        </section>

        <!-- 健康状况 -->
        <section>
          <h3 class="text-hint font-medium text-ink-muted">健康状况（可多选）</h3>
          <div class="mt-1.5 flex flex-wrap gap-1.5">
            <button
              v-for="o in options?.conditions ?? []"
              :key="o.value"
              type="button"
              class="rounded-full border px-3 py-1 text-body transition-colors max-md:min-h-10 max-md:px-4"
              :class="chipClass(draft.conditions.includes(o.value))"
              @click="toggleMulti('conditions', o.value)"
            >
              {{ o.label }}
            </button>
          </div>
          <p v-if="!options" class="mt-1.5 text-hint text-ink-muted">选项加载中…</p>

          <label class="mt-3 block">
            <span class="text-hint text-ink-muted">过敏史</span>
            <input
              v-model="draft.allergies"
              type="text"
              placeholder="如：青霉素、虾蟹"
              class="mt-1 w-full rounded-input border border-line bg-surface px-3 py-2 text-body text-ink outline-none placeholder:text-ink-muted focus:border-brand"
            />
          </label>
          <label class="mt-3 block">
            <span class="text-hint text-ink-muted">长期用药</span>
            <input
              v-model="draft.medications"
              type="text"
              placeholder="如：氨氯地平 5mg 每日一次"
              class="mt-1 w-full rounded-input border border-line bg-surface px-3 py-2 text-body text-ink outline-none placeholder:text-ink-muted focus:border-brand"
            />
          </label>
        </section>

        <!-- 生活方式 -->
        <section>
          <h3 class="text-hint font-medium text-ink-muted">生活方式</h3>

          <p class="mt-2 text-hint text-ink-muted">运动频率</p>
          <div class="mt-1.5 flex flex-wrap gap-1.5">
            <button
              v-for="o in options?.exercise ?? []"
              :key="o.value"
              type="button"
              class="rounded-full border px-3 py-1 text-body transition-colors max-md:min-h-10 max-md:px-4"
              :class="chipClass(isOn('exercise', o.value))"
              @click="toggleSingle('exercise', o.value)"
            >
              {{ o.label }}
            </button>
          </div>

          <label class="mt-3 block">
            <span class="text-hint text-ink-muted">平均睡眠（小时 / 天）</span>
            <input
              :value="draft.sleepHours ?? ''"
              type="number"
              step="0.5"
              inputmode="decimal"
              placeholder="7"
              class="mt-1 w-full rounded-input border bg-surface px-3 py-2 text-body text-ink outline-none focus:border-brand"
              :class="errors.sleepHours ? 'border-danger' : 'border-line'"
              @input="onNumber('sleepHours', $event)"
            />
            <span v-if="errors.sleepHours" class="mt-1 block text-hint text-danger">
              {{ errors.sleepHours }}
            </span>
          </label>

          <p class="mt-3 text-hint text-ink-muted">吸烟</p>
          <div class="mt-1.5 flex flex-wrap gap-1.5">
            <button
              v-for="o in options?.smoking ?? []"
              :key="o.value"
              type="button"
              class="rounded-full border px-3 py-1 text-body transition-colors max-md:min-h-10 max-md:px-4"
              :class="chipClass(isOn('smoking', o.value))"
              @click="toggleSingle('smoking', o.value)"
            >
              {{ o.label }}
            </button>
          </div>

          <p class="mt-3 text-hint text-ink-muted">饮酒</p>
          <div class="mt-1.5 flex flex-wrap gap-1.5">
            <button
              v-for="o in options?.drinking ?? []"
              :key="o.value"
              type="button"
              class="rounded-full border px-3 py-1 text-body transition-colors max-md:min-h-10 max-md:px-4"
              :class="chipClass(isOn('drinking', o.value))"
              @click="toggleSingle('drinking', o.value)"
            >
              {{ o.label }}
            </button>
          </div>

          <p class="mt-3 text-hint text-ink-muted">饮食偏好（可多选）</p>
          <div class="mt-1.5 flex flex-wrap gap-1.5">
            <button
              v-for="o in options?.diet ?? []"
              :key="o.value"
              type="button"
              class="rounded-full border px-3 py-1 text-body transition-colors max-md:min-h-10 max-md:px-4"
              :class="chipClass(draft.diet.includes(o.value))"
              @click="toggleMulti('diet', o.value)"
            >
              {{ o.label }}
            </button>
          </div>
        </section>

        <!-- 目标 -->
        <section>
          <h3 class="text-hint font-medium text-ink-muted">健康目标（可多选）</h3>
          <div class="mt-1.5 flex flex-wrap gap-1.5">
            <button
              v-for="o in options?.goals ?? []"
              :key="o.value"
              type="button"
              class="rounded-full border px-3 py-1 text-body transition-colors max-md:min-h-10 max-md:px-4"
              :class="chipClass(draft.goals.includes(o.value))"
              @click="toggleMulti('goals', o.value)"
            >
              {{ o.label }}
            </button>
          </div>
        </section>

        <!-- 补充 -->
        <section>
          <h3 class="text-hint font-medium text-ink-muted">补充说明</h3>
          <textarea
            v-model="draft.note"
            rows="3"
            maxlength="1000"
            placeholder="如：工作需久坐，晚上常加班；希望能给我容易坚持的建议"
            class="mt-1.5 w-full resize-y rounded-input border border-line bg-surface px-3 py-2 text-body text-ink outline-none placeholder:text-ink-muted focus:border-brand"
          />
          <p class="mt-1 text-right text-hint text-ink-muted/70">{{ draft.note.length }} / 1000</p>
        </section>
      </div>

      <!-- 底部操作 -->
      <footer class="flex shrink-0 items-center gap-2 border-t border-line px-4 py-3 pb-safe">
        <button
          v-if="portrait?.exists"
          type="button"
          class="rounded-btn px-3 py-2 text-hint transition-colors disabled:opacity-40 max-md:min-h-11"
          :class="confirmClear ? 'bg-danger/10 font-medium text-danger' : 'text-danger/70 hover:text-danger'"
          :disabled="busy"
          @click="doClear"
        >
          {{ confirmClear ? '确认清空' : '清空画像' }}
        </button>
        <span v-if="completenessNow > 0" class="tnum ml-auto text-hint text-ink-muted">
          完善度 {{ completenessNow }}%
        </span>
        <button
          type="button"
          class="rounded-btn grad-primary px-5 py-2 text-body transition-transform hover:scale-[1.02] active:scale-[0.98] disabled:opacity-40 disabled:hover:scale-100 max-md:min-h-11"
          :class="completenessNow > 0 ? '' : 'ml-auto'"
          :disabled="busy"
          @click="save"
        >
          {{ busy ? '保存中…' : '保存画像' }}
        </button>
      </footer>
    </template>
  </SideSheet>
</template>

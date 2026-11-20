<script setup lang="ts">
/* ============================================================
 * 健康画像卡（docs/V2/08 §6.3）
 *
 * 设计意图：把「一批字段」画成「一个人」。
 *
 * 三条刻意的取舍：
 *   1. 不罗列字段名。年龄/身高体重/BMI 排成一行**读数**（font-display + 等宽数字），
 *      尺寸明显大于正文——它们要像仪表盘的读数，而不是表单的回显；
 *   2. BMI 用**四段刻度 + 指针**而不是一个数字。数字要心算才知道好坏，
 *      刻度把"我在哪一档"变成一眼可读的位置；
 *   3. 未填项做成**虚线虚位且可点击**。空表单填一半就放弃，通常是因为
 *      "不知道还差什么"；把缺口本身变成长在卡上的按钮，比一句进度数字有效。
 *
 * 视觉沿用 Vital 双温区：慢病用暖（警示 / --c-accent），目标用冷（正向 / --c-brand），
 * 不引入第三种强调色，也不引入新字体。
 * ============================================================ */

import { computed } from 'vue'
import {
  ageOf,
  bmiBand,
  bmiOf,
  filledCountOf,
  isStale,
  labelsOf,
  PORTRAIT_FIELD_TOTAL
} from '@/api/portrait'
import type { Portrait, PortraitOptions } from '@/api/portrait'

const props = defineProps<{
  portrait: Portrait | null
  options: PortraitOptions | null
  loading?: boolean
}>()

const emit = defineEmits<{ (e: 'edit'): void }>()

/* ---------------- 派生 ---------------- */

const exists = computed(() => props.portrait?.exists === true)

const bmi = computed(() =>
  props.portrait ? bmiOf(props.portrait.heightCm, props.portrait.weightKg) : null
)
const band = computed(() => bmiBand(bmi.value))

/** 指针颜色与所在区间一致（颜色之外还有位置，不靠颜色单独传达含义） */
const pointerTone = computed(() => {
  switch (band.value?.key) {
    case 'thin':
      return 'border-brand bg-brand'
    case 'normal':
      return 'border-success bg-success'
    case 'over':
      return 'border-warning bg-warning'
    case 'obese':
      return 'border-danger bg-danger'
    default:
      return 'border-line bg-ink-muted'
  }
})

const age = computed(() => (props.portrait ? ageOf(props.portrait.birthYear) : null))

const conditions = computed(
  () => labelsOf(props.options?.conditions, props.portrait?.conditions ?? [])
)
const goals = computed(() => labelsOf(props.options?.goals, props.portrait?.goals ?? []))
const diet = computed(() => labelsOf(props.options?.diet, props.portrait?.diet ?? []))

/**
 * 未填的关键项：卡上以虚位呈现，点击直达编辑。
 * 只挑对回答影响最大的几项，全部列出来会变成第二张表单。
 */
const unfilled = computed<string[]>(() => {
  const p = props.portrait
  if (!p) return []
  const gaps: string[] = []
  if (p.heightCm == null || p.weightKg == null) gaps.push('身高体重')
  if (p.conditions.length === 0) gaps.push('健康状况')
  if (p.goals.length === 0) gaps.push('健康目标')
  if (p.exercise == null) gaps.push('运动习惯')
  if (p.sleepHours == null) gaps.push('睡眠时长')
  return gaps
})

/** 完善度以服务端值为准；缺失时才本地兜底（避免首屏闪一个错的数） */
const completeness = computed(() => {
  const p = props.portrait
  if (!p) return 0
  return p.exists ? p.completeness : 0
})

const filledLabel = computed(() => {
  const p = props.portrait
  if (!p || !p.exists) return `0 / ${PORTRAIT_FIELD_TOTAL}`
  return `${p.filledCount || filledCountOf(p)} / ${p.totalCount || PORTRAIT_FIELD_TOTAL}`
})

const stale = computed(() => isStale(props.portrait?.updateTime ?? null))

const lifestyle = computed(() => {
  const p = props.portrait
  if (!p) return []
  const rows: Array<{ k: string; v: string }> = []
  const ex = props.options?.exercise.find((o) => o.value === p.exercise)?.label
  if (ex) rows.push({ k: '运动', v: ex })
  if (p.sleepHours != null) rows.push({ k: '睡眠', v: `${p.sleepHours} 小时 / 天` })
  const sm = props.options?.smoking.find((o) => o.value === p.smoking)?.label
  if (sm) rows.push({ k: '吸烟', v: sm })
  const dr = props.options?.drinking.find((o) => o.value === p.drinking)?.label
  if (dr) rows.push({ k: '饮酒', v: dr })
  return rows
})

const hasMedicalText = computed(
  () => !!(props.portrait?.allergies || props.portrait?.medications || props.portrait?.note)
)
</script>

<template>
  <section class="rounded-card border border-line bg-surface p-4 sm:p-5" aria-label="健康画像">
    <!-- 骨架：与最终结构的尺寸对齐，避免加载完成时页面跳动 -->
    <div v-if="loading" aria-busy="true">
      <div class="shimmer h-3 w-24 rounded-full" />
      <div class="shimmer mt-4 h-8 w-2/3 rounded-full" />
      <div class="shimmer mt-4 h-2 w-full rounded-full" />
      <div class="shimmer mt-4 h-16 w-full rounded-card" />
    </div>

    <!-- 空态：把"为什么值得填"讲清楚，而不是只给一个按钮 -->
    <div v-else-if="!exists" class="text-center">
      <span
        class="mx-auto flex h-12 w-12 items-center justify-center rounded-full border border-dashed border-line-strong text-ink-muted"
        aria-hidden="true"
      >
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="8" r="4" />
          <path d="M4 21c0-3.9 3.6-7 8-7s8 3.1 8 7" />
        </svg>
      </span>
      <p class="mt-3 text-section font-medium text-ink">还没有健康画像</p>
      <p class="mx-auto mt-1 max-w-md text-hint text-ink-muted">
        填一份你的基本情况（身高体重、慢病、目标、作息）。之后在
        <span class="text-ink">专属对话</span>里提问，回答会自动结合这些信息，
        不用每次重复交代背景。
      </p>
      <button
        type="button"
        class="mt-5 rounded-btn grad-primary px-5 py-2.5 text-body transition-transform hover:scale-[1.02] active:scale-[0.98] max-md:min-h-11"
        @click="emit('edit')"
      >
        开始填写画像
      </button>
      <p class="mt-3 text-hint text-ink-muted/80">
        全部字段都可选填 · 只用于让回答贴合你的情况 · 不会出现在管理端可见的日志里
      </p>
    </div>

    <!-- 已填写 -->
    <template v-else>
      <header class="flex items-start gap-3">
        <div class="min-w-0 flex-1">
          <h2 class="flex items-center gap-2 text-section font-medium text-ink">
            我的健康画像
            <span
              class="rounded-full border border-line px-2 py-0.5 text-[11px] font-normal text-ink-muted"
            >
              已填 {{ filledLabel }} 项
            </span>
          </h2>
          <p v-if="stale" class="mt-1 text-hint text-warning">
            画像已有半年以上未更新，体重或目标变化后建议及时刷新。
          </p>
        </div>
        <button
          type="button"
          class="shrink-0 rounded-btn border border-line px-3 py-1.5 text-hint text-ink-muted transition-colors hover:border-brand hover:text-brand max-md:min-h-11"
          @click="emit('edit')"
        >
          编辑
        </button>
      </header>

      <!-- 完善度 -->
      <div class="mt-3 h-1 overflow-hidden rounded-full bg-surface-2" role="progressbar" :aria-valuenow="completeness" aria-valuemin="0" aria-valuemax="100">
        <div
          class="h-full rounded-full transition-[width] duration-500"
          :style="{
            width: `${completeness}%`,
            backgroundImage: 'linear-gradient(90deg, var(--grad-a), var(--grad-b))'
          }"
        />
      </div>

      <!-- 体征读数：年龄 / 身高 / 体重 -->
      <div class="mt-4 grid grid-cols-3 gap-3">
        <div>
          <p class="text-hint text-ink-muted">年龄</p>
          <p class="font-display tnum mt-0.5 text-section text-ink">
            <template v-if="age != null">{{ age }}<span class="ml-0.5 text-hint">岁</span></template>
            <span v-else class="text-hint text-ink-muted">未填写</span>
          </p>
        </div>
        <div>
          <p class="text-hint text-ink-muted">身高</p>
          <p class="font-display tnum mt-0.5 text-section text-ink">
            <template v-if="portrait?.heightCm != null">
              {{ portrait.heightCm }}<span class="ml-0.5 text-hint">cm</span>
            </template>
            <span v-else class="text-hint text-ink-muted">未填写</span>
          </p>
        </div>
        <div>
          <p class="text-hint text-ink-muted">体重</p>
          <p class="font-display tnum mt-0.5 text-section text-ink">
            <template v-if="portrait?.weightKg != null">
              {{ portrait.weightKg }}<span class="ml-0.5 text-hint">kg</span>
            </template>
            <span v-else class="text-hint text-ink-muted">未填写</span>
          </p>
        </div>
      </div>

      <!-- BMI 刻度：把数字变成位置 -->
      <div class="mt-5">
        <div class="flex items-baseline justify-between">
          <span class="text-hint text-ink-muted">体重指数 BMI</span>
          <span class="font-display tnum text-body text-ink">
            <template v-if="bmi != null">
              {{ bmi.toFixed(1) }}
              <span v-if="band" class="ml-1 text-hint" :class="band.tone">{{ band.label }}</span>
            </template>
            <span v-else class="text-hint text-ink-muted">需身高与体重</span>
          </span>
        </div>
        <div class="relative mt-2.5 h-1.5 rounded-full">
          <div class="absolute inset-0 flex overflow-hidden rounded-full">
            <span class="h-full flex-1 bg-brand/25" />
            <span class="h-full flex-1 bg-success/30" />
            <span class="h-full flex-1 bg-warning/30" />
            <span class="h-full flex-1 bg-danger/30" />
          </div>
          <span
            v-if="band"
            class="absolute -top-[5px] h-3.5 w-3.5 -translate-x-1/2 rounded-full border-2 border-surface transition-[left] duration-500"
            :class="pointerTone"
            :style="{ left: `${band.position}%` }"
            aria-hidden="true"
          />
        </div>
        <div class="mt-1 flex justify-between text-[11px] text-ink-muted/80">
          <span>偏瘦</span><span>正常</span><span>超重</span><span>肥胖</span>
        </div>
      </div>

      <!-- 标签：慢病暖色（警示语义）/ 目标冷色（正向语义） -->
      <div v-if="conditions.length" class="mt-4">
        <p class="text-hint text-ink-muted">健康状况</p>
        <div class="mt-1.5 flex flex-wrap gap-1.5">
          <span
            v-for="c in conditions"
            :key="c"
            class="rounded-full border border-accent/40 px-2 py-0.5 text-hint text-accent"
          >
            {{ c }}
          </span>
        </div>
      </div>

      <div v-if="goals.length" class="mt-3">
        <p class="text-hint text-ink-muted">健康目标</p>
        <div class="mt-1.5 flex flex-wrap gap-1.5">
          <span
            v-for="g in goals"
            :key="g"
            class="rounded-full border border-brand/40 px-2 py-0.5 text-hint text-brand"
          >
            {{ g }}
          </span>
        </div>
      </div>

      <div v-if="diet.length" class="mt-3">
        <p class="text-hint text-ink-muted">饮食偏好</p>
        <div class="mt-1.5 flex flex-wrap gap-1.5">
          <span
            v-for="d in diet"
            :key="d"
            class="rounded-full border border-line px-2 py-0.5 text-hint text-ink-muted"
          >
            {{ d }}
          </span>
        </div>
      </div>

      <!-- 生活方式：键值对，读取优先 -->
      <dl v-if="lifestyle.length" class="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 sm:grid-cols-4">
        <div v-for="row in lifestyle" :key="row.k">
          <dt class="text-hint text-ink-muted">{{ row.k }}</dt>
          <dd class="text-body text-ink">{{ row.v }}</dd>
        </div>
      </dl>

      <p v-if="hasMedicalText" class="mt-3 text-hint text-ink-muted">
        <template v-if="portrait?.allergies">过敏史：{{ portrait.allergies }}　</template>
        <template v-if="portrait?.medications">长期用药：{{ portrait.medications }}</template>
        <template v-if="portrait?.note">
          <span v-if="portrait?.allergies || portrait?.medications" class="block" />
          {{ portrait.note }}
        </template>
      </p>

      <!-- 未填虚位：把缺口变成可点击的行动 -->
      <div v-if="unfilled.length" class="mt-4">
        <p class="text-hint text-ink-muted">还差这些（点击补齐）</p>
        <div class="mt-1.5 flex flex-wrap gap-1.5">
          <button
            v-for="gap in unfilled"
            :key="gap"
            type="button"
            class="rounded-full border border-dashed border-line-strong px-2.5 py-1 text-hint text-ink-muted transition-colors hover:border-brand hover:text-brand max-md:min-h-9"
            @click="emit('edit')"
          >
            + {{ gap }}
          </button>
        </div>
      </div>

      <p class="mt-4 border-t border-line pt-3 text-hint text-ink-muted/80">
        仅在「专属对话」中作为回答依据使用；不会进入检索、不会写入管理端可见的日志。
      </p>
    </template>
  </section>
</template>

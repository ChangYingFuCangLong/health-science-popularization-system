<script setup lang="ts">
/* ============================================================
 * 个人健康科普 · 健康详情（/health/detail，F-12 六区归一）
 *   画像卡（F-10 迁入）/ 指标卡区（F-11 目录化）/ 健康备忘 KV 区 /
 *   报告识读（✔确认 ✖取消 ✎编辑 三键）/ 趋势与 AI 解读
 * 防臃肿四原则（10 号 §1.2）：只渲染有数据的指标卡；
 * 添加指标 = 分类选择器（标准 + 我的自定义）；空指标不占版面。
 * ============================================================ */

import { computed, onMounted, reactive, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import PortraitCard from '@/components/PortraitCard.vue'
import PortraitEditor from '@/components/PortraitEditor.vue'
import { useRouter } from 'vue-router'
import { useChart, chartColor } from '@/composer/useChart'
import { useTheme } from '@/composer/useTheme'
import { showMessage } from '@/utils/message'
import * as healthApi from '@/api/health'
import type { HealthKvItem, HealthMetric, MetricDef } from '@/api/health'
import type { Portrait, PortraitOptions } from '@/api/portrait'
import { validateFile } from '@/api/userKb'
import { UPLOAD_ACCEPT } from '@/constants/upload'
import { getPortrait, getPortraitOptions } from '@/api/portrait'
import { listHealthSessions } from '@/api/chat'

const { mode: themeMode } = useTheme()
const router = useRouter()

/* ---------------- 画像（#54-#57；编辑表单复用 PortraitEditor） ---------------- */
const editorOpen = ref(false)
const portrait = ref<Portrait | null>(null)
const portraitOptions = ref<PortraitOptions | null>(null)

async function loadPortrait(): Promise<void> {
  try {
    portrait.value = await getPortrait({ silent: true })
  } catch {
    portrait.value = null
  }
}

async function loadPortraitOptions(): Promise<void> {
  try {
    portraitOptions.value = await getPortraitOptions({ silent: true })
  } catch {
    portraitOptions.value = null
  }
}

/* ---------------- 指标目录与记录 ---------------- */
const catalog = ref<healthApi.MetricCatalog | null>(null)
const records = ref<HealthMetric[]>([])
const activeKey = ref<string | null>(null)
const showAdd = ref(false)
const showCustomForm = ref(false)

/** 平铺目录（含自定义），按 key 查定义 */
const allDefs = computed<MetricDef[]>(() => {
  const cats = catalog.value?.categories ?? []
  return cats.flatMap((c) => c.metrics)
})

function defOf(key: string): MetricDef | undefined {
  return allDefs.value.find((m) => m.key === key)
}

/** 有记录的 key（渐进披露：只渲染有数据的指标卡） */
const activeKeys = computed(() => [...new Set(records.value.map((r) => r.type))])

function recordsOf(key: string): HealthMetric[] {
  return records.value.filter((r) => r.type === key).sort((a, b) => a.recordedAt.localeCompare(b.recordedAt))
}

function displayValue(key: string, m: HealthMetric): string {
  const def = defOf(key)
  const unit = def?.unit ?? ''
  if (def?.hasValue2) return `${m.value}/${m.value2 ?? '-'} ${unit}`
  return `${m.value} ${unit}`
}

const activeDef = computed(() => (activeKey.value ? defOf(activeKey.value) : undefined))
const activeRecords = computed(() => (activeKey.value ? recordsOf(activeKey.value) : []))

/* 录入表单（按目录定义驱动） */
const form = reactive({ value: '', value2: '', recordedAt: '', note: '' })
const saving = ref(false)

function nowForInput(): string {
  const d = new Date()
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function submitRecord(): Promise<void> {
  const def = activeDef.value
  if (!def) return
  const v = Number(form.value)
  if (!Number.isFinite(v) || v <= 0) {
    showMessage('请填写有效数值', 'warning')
    return
  }
  if (def.hasValue2 && !Number.isFinite(Number(form.value2))) {
    showMessage(`请填写${def.value2Label ?? '第二数值'}`, 'warning')
    return
  }
  saving.value = true
  try {
    await healthApi.createHealthMetric({
      type: def.key,
      value: v,
      value2: def.hasValue2 ? Number(form.value2) : null,
      recordedAt: form.recordedAt.replace('T', ' ') + (form.recordedAt.length === 16 ? ':00' : ''),
      note: form.note.trim() || undefined
    })
    showMessage('已记录', 'success')
    form.value = ''
    form.value2 = ''
    form.note = ''
    await loadRecords()
  } catch {
    /* 3001 越界等：拦截器已提示 */
  } finally {
    saving.value = false
  }
}

/* 自定义指标（#61/#62） */
const customForm = reactive({ label: '', unit: '', valueMin: '', valueMax: '', hasValue2: false })
const creatingDef = ref(false)

async function submitCustomDef(): Promise<void> {
  if (!customForm.label.trim()) {
    showMessage('请填写指标名称', 'warning')
    return
  }
  creatingDef.value = true
  try {
    await healthApi.createMetricDef({
      label: customForm.label.trim(),
      unit: customForm.unit.trim() || undefined,
      valueMin: customForm.valueMin === '' ? null : Number(customForm.valueMin),
      valueMax: customForm.valueMax === '' ? null : Number(customForm.valueMax),
      hasValue2: customForm.hasValue2
    })
    showMessage('已添加自定义指标', 'success')
    customForm.label = ''
    customForm.unit = ''
    customForm.valueMin = ''
    customForm.valueMax = ''
    customForm.hasValue2 = false
    showCustomForm.value = false
    await loadCatalog()
  } catch {
    /* 2006 上限 / 400 重名：拦截器已提示 */
  } finally {
    creatingDef.value = false
  }
}

async function removeDef(def: MetricDef): Promise<void> {
  if (!def.custom || !def.defId) return
  try {
    await healthApi.deleteMetricDef(def.defId)
    showMessage('已删除（历史记录保留只读）', 'info')
    if (activeKey.value === def.key) activeKey.value = null
    await loadCatalog()
  } catch {
    /* 标准指标 400 / 越权 404：拦截器已提示 */
  }
}

/* ---------------- 健康备忘 KV（#65-#68） ---------------- */
const kvList = ref<HealthKvItem[]>([])
const kvForm = reactive({ itemKey: '', itemValue: '' })
const kvSaving = ref(false)

async function loadKv(): Promise<void> {
  try {
    kvList.value = await healthApi.listHealthKv({ silent: true })
  } catch {
    kvList.value = []
  }
}

async function submitKv(): Promise<void> {
  if (!kvForm.itemKey.trim() || !kvForm.itemValue.trim()) {
    showMessage('键与内容都不能为空', 'warning')
    return
  }
  kvSaving.value = true
  try {
    await healthApi.createHealthKv(kvForm.itemKey.trim(), kvForm.itemValue.trim())
    kvForm.itemKey = ''
    kvForm.itemValue = ''
    await loadKv()
    showMessage('已记录', 'success')
  } catch {
    /* 2006 上限 50 条：拦截器已提示 */
  } finally {
    kvSaving.value = false
  }
}

async function removeKv(item: HealthKvItem): Promise<void> {
  try {
    await healthApi.deleteHealthKv(item.id)
    await loadKv()
  } catch {
    /* 拦截器已提示 */
  }
}

/* ---------------- 报告识读（#63/#64 两步流，确认制） ---------------- */
const CONSENT_KEY = 'report-consent'
const consented = ref(localStorage.getItem(CONSENT_KEY) === '1')
const reportInput = ref<HTMLInputElement | null>(null)
const extracting = ref(false)
/** 抽取草稿：✔确认入库 ✖取消（前端丢弃，零痕迹） ✎行内编辑 */
const draft = ref<healthApi.ReportExtractResult | null>(null)
const alsoToKb = ref(true)
const committing = ref(false)

function onConsent(): void {
  consented.value = true
  localStorage.setItem(CONSENT_KEY, '1')
}

async function onReportPick(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const problem = validateFile(file)
  if (problem) {
    showMessage(problem, 'warning')
    return
  }
  extracting.value = true
  try {
    draft.value = await healthApi.extractReport(file, alsoToKb.value, { silent: false })
  } catch {
    /* 2001/2007：拦截器已提示 */
  } finally {
    extracting.value = false
  }
}

function cancelDraft(): void {
  draft.value = null // 草稿不入库，取消 = 零痕迹
}

async function commitDraft(): Promise<void> {
  if (!draft.value) return
  committing.value = true
  try {
    const res = await healthApi.commitReport({
      draftId: draft.value.draftId,
      reportDate: draft.value.reportDate,
      items: draft.value.items
    })
    showMessage(`已入库：指标 ${res.metricCount} 项、备忘 ${res.kvCount} 条`, 'success')
    draft.value = null
    await Promise.all([loadRecords(), loadKv()])
  } catch {
    /* 400 草稿过期等：拦截器已提示 */
  } finally {
    committing.value = false
  }
}

/* ---------------- 趋势图 + AI 解读 ---------------- */
const chartEl = ref<HTMLElement | null>(null)

function shortTime(raw: string): string {
  return raw.replace('T', ' ').slice(5, 16)
}

function buildOption(): EChartsCoreOption | null {
  const list = activeRecords.value
  if (list.length === 0 || !activeDef.value) return null
  const labels = list.map((m) => shortTime(m.recordedAt))
  const tooltip = {
    trigger: 'axis' as const,
    backgroundColor: chartColor('--c-surface-2'),
    borderColor: chartColor('--c-line'),
    textStyle: { color: chartColor('--c-ink'), fontSize: 12 }
  }
  const series = {
    type: 'line' as const,
    data: list.map((m) => m.value),
    smooth: true,
    symbolSize: 6,
    lineStyle: { color: chartColor('--c-brand'), width: 2.5 },
    itemStyle: { color: chartColor('--c-brand') },
    areaStyle: { color: 'rgba(20,184,166,0.10)' }
  }
  if (activeDef.value?.hasValue2) {
    return {
      grid: { left: 40, right: 16, top: 16, bottom: 28 },
      tooltip,
      xAxis: { type: 'category', data: labels, axisLabel: { color: chartColor('--c-ink-muted'), fontSize: 11 } },
      yAxis: { type: 'value', axisLabel: { color: chartColor('--c-ink-muted') }, splitLine: { lineStyle: { color: chartColor('--c-line') } } },
      series: [series, { ...series, data: list.map((m) => m.value2 ?? null) }]
    }
  }
  return {
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    tooltip,
    xAxis: { type: 'category', data: labels, axisLabel: { color: chartColor('--c-ink-muted'), fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { color: chartColor('--c-ink-muted') }, splitLine: { lineStyle: { color: chartColor('--c-line') } } },
    series
  }
}

useChart(chartEl, buildOption, [activeRecords, activeDef, themeMode])

function askAi(): void {
  const key = activeKey.value
  if (!key || activeRecords.value.length === 0) {
    showMessage('先选择有记录的指标', 'info')
    return
  }
  const def = activeDef.value
  const lines = activeRecords.value
    .slice(-10)
    .map((m) => `${shortTime(m.recordedAt)}：${displayValue(key, m)}`)
    .join('\n')
  // 预填到个人解惑：健康上下文四层注入由服务端组装，这里只给原始数据
  sessionStorage.setItem(
    'health-rag-prefill',
    `以下是我近期记录的${def?.label ?? key}数据：\n${lines}\n\n请分析趋势、指出需警惕的信号，并给出可执行建议。`
  )
  // 有个人解惑 → 进入最近一条（对话页挂载即消费预填）；没有 → 回概览创建
  void listHealthSessions({ silent: true })
    .then((list) => {
      const latest = list[list.length - 1]
      void router.push(latest ? `/health/chat/${latest.id}` : '/health')
    })
    .catch(() => void router.push('/health'))
}

/* ---------------- 加载 ---------------- */
async function loadCatalog(): Promise<void> {
  try {
    catalog.value = await healthApi.getMetricCatalog({ silent: true })
  } catch {
    catalog.value = null
  }
}

async function loadRecords(): Promise<void> {
  try {
    const res = await healthApi.listHealthMetrics({ days: 90 }, { silent: true })
    records.value = (res.records ?? []).sort((a, b) => a.recordedAt.localeCompare(b.recordedAt))
    if (!activeKey.value && records.value.length) activeKey.value = records.value[records.value.length - 1].type
  } catch {
    records.value = []
  }
}

onMounted(() => {
  form.recordedAt = nowForInput()
  void loadCatalog()
  void loadRecords()
  void loadKv()
  void loadPortrait()
  void loadPortraitOptions()
})
</script>

<template>
  <div class="mx-auto max-w-list px-4 py-6">
    <div class="flex items-center justify-between gap-3">
      <div>
        <h1 class="text-display font-semibold text-ink">健康详情</h1>
        <p class="mt-1 text-body text-ink-muted">画像、指标、备忘与报告——个人解惑的四层数据都来自这里。</p>
      </div>
      <router-link
        to="/health"
        class="shrink-0 rounded-btn border border-line px-3 py-1.5 text-body text-ink-muted hover:border-brand hover:text-brand"
      >
        ← 返回概览
      </router-link>
    </div>

    <!-- ① 画像卡（F-10 迁入） -->
    <section class="mt-4">
      <PortraitCard :portrait="portrait" :options="portraitOptions" @edit="editorOpen = true" />
    </section>

    <!-- ② 指标区（目录化，F-11） -->
    <section class="mt-4 rounded-card border border-line bg-surface p-4">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h2 class="text-section font-medium text-ink">健康指标</h2>
        <div class="flex gap-2">
          <button type="button" class="rounded-btn border border-line px-3 py-1.5 text-hint text-ink-muted hover:border-brand hover:text-brand" @click="showAdd = !showAdd">
            添加指标
          </button>
          <button type="button" class="rounded-btn border border-line px-3 py-1.5 text-hint text-ink-muted hover:border-brand hover:text-brand" @click="showCustomForm = !showCustomForm">
            自定义指标
          </button>
        </div>
      </div>

      <!-- 添加指标：分类选择器（标准 + 我的自定义） -->
      <div v-if="showAdd" class="mt-3 space-y-2 rounded-card border border-line bg-canvas p-3">
        <div v-for="cat in catalog?.categories ?? []" :key="cat.name">
          <p class="text-hint text-ink-muted">{{ cat.name }}</p>
          <div class="mt-1 flex flex-wrap gap-1.5">
            <button
              v-for="m in cat.metrics"
              :key="m.key"
              type="button"
              class="rounded-full border px-3 py-1 text-hint"
              :class="activeKey === m.key ? 'border-brand bg-brand-soft text-brand' : 'border-line text-ink-muted hover:border-brand hover:text-brand'"
              @click="activeKey = m.key; showAdd = false"
            >
              {{ m.label }}<span v-if="m.custom" class="ml-1 text-[10px]">自定义</span>
            </button>
          </div>
        </div>
      </div>

      <!-- 自定义指标创建（#61，≤10 个 → 2006） -->
      <form v-if="showCustomForm" class="mt-3 space-y-2 rounded-card border border-line bg-canvas p-3" @submit.prevent="submitCustomDef">
        <div class="flex flex-wrap gap-2">
          <input v-model="customForm.label" type="text" placeholder="名称（必填）" class="min-w-0 flex-1 rounded-input border border-line bg-surface px-3 py-2 text-body text-ink outline-none focus:border-brand" />
          <input v-model="customForm.unit" type="text" placeholder="单位" class="w-24 rounded-input border border-line bg-surface px-3 py-2 text-body text-ink outline-none focus:border-brand" />
          <input v-model="customForm.valueMin" type="number" placeholder="范围下限" class="w-28 rounded-input border border-line bg-surface px-3 py-2 text-body text-ink outline-none focus:border-brand" />
          <input v-model="customForm.valueMax" type="number" placeholder="范围上限" class="w-28 rounded-input border border-line bg-surface px-3 py-2 text-body text-ink outline-none focus:border-brand" />
        </div>
        <button type="submit" :disabled="creatingDef" class="rounded-btn grad-primary px-4 py-1.5 text-body disabled:opacity-40">
          创建（≤10 个）
        </button>
      </form>

      <!-- 指标卡（只渲染有数据的，渐进披露） -->
      <div class="mt-3 flex flex-wrap gap-2">
        <button
          v-for="key in activeKeys"
          :key="key"
          type="button"
          class="card-lift rounded-card border p-3 text-left"
          :class="activeKey === key ? 'border-brand bg-brand-soft' : 'border-line bg-surface'"
          @click="activeKey = key"
        >
          <p class="text-hint text-ink-muted">
            {{ defOf(key)?.label ?? key }}<span v-if="!defOf(key)" class="ml-1 text-[10px]">已删除指标</span>
          </p>
          <p class="tnum mt-0.5 text-body font-medium text-ink">
            {{ displayValue(key, recordsOf(key)[recordsOf(key).length - 1]) }}
          </p>
        </button>
        <p v-if="activeKeys.length === 0" class="text-body text-ink-muted">
          还没有指标记录。点「添加指标」从目录选择，或创建自定义指标。
        </p>
      </div>

      <!-- 选中指标的录入 + 趋势 + AI 解读 -->
      <div v-if="activeDef" class="mt-4 grid gap-4 lg:grid-cols-5">
        <div class="rounded-card border border-line p-4 lg:col-span-3">
          <div class="flex items-center justify-between">
            <h3 class="text-body font-medium text-ink">{{ activeDef.label }}趋势</h3>
            <button
              v-if="activeDef.custom && activeDef.defId"
              type="button"
              class="rounded-btn px-2 py-0.5 text-hint text-ink-muted hover:text-danger"
              @click="removeDef(activeDef)"
            >
              删除该自定义指标
            </button>
          </div>
          <div v-if="activeRecords.length === 0" class="flex h-48 items-center justify-center text-body text-ink-muted">
            暂无记录，从右侧录入
          </div>
          <div v-else ref="chartEl" class="mt-2 h-48" />
        </div>

        <div class="rounded-card border border-line p-4 lg:col-span-2">
          <h3 class="text-body font-medium text-ink">记录{{ activeDef.label }}</h3>
          <div class="mt-2 flex gap-2">
            <label class="min-w-0 flex-1">
              <span class="block pb-1 text-hint text-ink-muted">{{ activeDef.unit ?? '数值' }}</span>
              <input v-model="form.value" type="number" class="tnum w-full rounded-input border border-line bg-canvas px-3 py-2 text-body text-ink outline-none focus:border-brand" />
            </label>
            <label v-if="activeDef.hasValue2" class="min-w-0 flex-1">
              <span class="block pb-1 text-hint text-ink-muted">{{ activeDef.value2Label ?? '第二数值' }}</span>
              <input v-model="form.value2" type="number" class="tnum w-full rounded-input border border-line bg-canvas px-3 py-2 text-body text-ink outline-none focus:border-brand" />
            </label>
          </div>
          <label class="mt-2 block">
            <span class="block pb-1 text-hint text-ink-muted">时间</span>
            <input v-model="form.recordedAt" type="datetime-local" class="w-full rounded-input border border-line bg-canvas px-3 py-2 text-body text-ink outline-none focus:border-brand" />
          </label>
          <button type="button" :disabled="saving" class="mt-3 w-full rounded-btn grad-primary py-2 text-body disabled:opacity-40" @click="submitRecord">
            {{ saving ? '保存中…' : '保存记录' }}
          </button>
        </div>
      </div>

      <button
        v-if="activeDef && activeRecords.length > 0"
        type="button"
        class="mt-3 rounded-btn grad-primary px-4 py-2 text-body"
        @click="askAi"
      >
        让 AI 解读「{{ activeDef.label }}」（去个人解惑）
      </button>
    </section>

    <!-- ③ 报告识读（#63/#64 两步流，确认制） -->
    <section class="mt-4 rounded-card border border-line bg-surface p-4">
      <h2 class="text-section font-medium text-ink">报告识读</h2>
      <p class="mt-1 text-hint text-ink-muted">
        上传体检报告，AI 抽取候选项；你确认后数值入指标（source=report）、文本入备忘。
      </p>

      <label v-if="!consented" class="mt-3 flex items-start gap-2 rounded-card border border-warning bg-accent-soft/30 p-3 text-body">
        <input type="checkbox" class="mt-1" @change="onConsent" />
        <span class="text-ink">
          我知悉：上传的报告内容将发送给 AI 服务商进行结构化识别；草稿 10 分钟后自动过期，取消即零痕迹。
        </span>
      </label>

      <template v-if="consented">
        <div class="mt-3 flex flex-wrap items-center gap-3">
          <input
            ref="reportInput"
            type="file"
            :accept="UPLOAD_ACCEPT"
            class="hidden"
            @change="onReportPick"
          />
          <button
            type="button"
            class="rounded-btn grad-primary px-4 py-2 text-body disabled:opacity-40"
            :disabled="extracting || draft != null"
            @click="reportInput?.click()"
          >
            {{ extracting ? '识别中…' : '上传报告' }}
          </button>
          <label class="flex items-center gap-1.5 text-body text-ink-muted">
            <input v-model="alsoToKb" type="checkbox" />
            同时加入我的资料库（可被个人解惑引用）
          </label>
        </div>

        <!-- 候选确认卡：✔确认 ✖取消 ✎行内编辑 -->
        <div v-if="draft" class="mt-3 rounded-card border border-brand/40 bg-canvas p-3">
          <p class="text-body text-ink">
            识别到 <span class="tnum font-medium">{{ draft.items.length }}</span> 项候选
            <span v-if="draft.reportDate"> · 报告日期 {{ draft.reportDate }}</span>
            <span v-if="draft.kbDocumentId" class="text-brand"> · 已并入我的资料库</span>
          </p>
          <ul class="mt-2 space-y-2">
            <li v-for="(item, i) in draft.items" :key="i" class="rounded-card border border-line bg-surface p-2.5">
              <div class="flex flex-wrap items-center gap-2">
                <input v-model="item.key" type="text" class="w-32 rounded-input border border-line bg-canvas px-2 py-1 text-body text-ink outline-none focus:border-brand" />
                <input v-model="item.value" type="text" class="min-w-0 flex-1 rounded-input border border-line bg-canvas px-2 py-1 text-body text-ink outline-none focus:border-brand" />
                <span class="shrink-0 rounded-full bg-surface-2 px-2 py-0.5 text-[10px] text-ink-muted">
                  {{ item.type === 'metric' ? '数值' : '文本' }}
                  <template v-if="item.type === 'metric'"> → {{ item.metricKey ?? '未映射' }}</template>
                </span>
                <button
                  type="button"
                  class="shrink-0 rounded-btn px-2 py-0.5 text-hint text-ink-muted hover:text-danger"
                  @click="draft?.items.splice(i, 1)"
                >
                  移除
                </button>
              </div>
            </li>
          </ul>
          <div class="mt-3 flex gap-2">
            <button type="button" :disabled="committing" class="rounded-btn grad-primary px-4 py-1.5 text-body disabled:opacity-40" @click="commitDraft">
              {{ committing ? '入库中…' : '✔ 确认入库' }}
            </button>
            <button type="button" class="rounded-btn border border-line px-4 py-1.5 text-body text-ink-muted" @click="cancelDraft">
              ✖ 取消
            </button>
            <span class="ml-auto self-center text-hint text-ink-muted">草稿 10 分钟内有效；数值越界项将被跳过</span>
          </div>
        </div>
      </template>
    </section>

    <!-- ④ 健康备忘 KV（#65-#68） -->
    <section class="mt-4 rounded-card border border-line bg-surface p-4">
      <h2 class="text-section font-medium text-ink">健康备忘</h2>
      <p class="mt-1 text-hint text-ink-muted">信息型记录（过敏史、用药、医嘱…），注入个人解惑上下文；≤50 条。</p>

      <form class="mt-3 flex flex-wrap gap-2" @submit.prevent="submitKv">
        <input v-model="kvForm.itemKey" type="text" placeholder="条目名（如：过敏史）" class="w-40 rounded-input border border-line bg-canvas px-3 py-2 text-body text-ink outline-none focus:border-brand" />
        <input v-model="kvForm.itemValue" type="text" placeholder="内容（如：青霉素）" class="min-w-0 flex-1 rounded-input border border-line bg-canvas px-3 py-2 text-body text-ink outline-none focus:border-brand" />
        <button type="submit" :disabled="kvSaving" class="rounded-btn grad-primary px-4 py-2 text-body disabled:opacity-40">
          记录
        </button>
      </form>

      <ul v-if="kvList.length" class="mt-3 divide-y divide-line">
        <li v-for="item in kvList" :key="item.id" class="flex items-center gap-3 py-2">
          <span class="w-32 shrink-0 truncate text-body text-ink">{{ item.itemKey }}</span>
          <span class="min-w-0 flex-1 truncate text-body text-ink-muted">{{ item.itemValue }}</span>
          <span v-if="item.source === 'report'" class="shrink-0 rounded-full bg-surface-2 px-2 py-0.5 text-[10px] text-ink-muted">报告</span>
          <button type="button" class="shrink-0 rounded-btn px-2 py-0.5 text-hint text-ink-muted hover:text-danger" @click="removeKv(item)">
            删除
          </button>
        </li>
      </ul>
      <p v-else class="mt-3 text-body text-ink-muted">暂无备忘。</p>
    </section>

    <PortraitEditor :open="editorOpen" :portrait="portrait" :options="portraitOptions" :need-consent="!consented" @close="editorOpen = false" @saved="loadPortrait" @consent="onConsent" />
  </div>
</template>

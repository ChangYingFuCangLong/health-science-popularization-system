/* ============================================================
 * 用户健康画像（02 v4.0 §6.2，#54–#57 · 设计依据 08 号）
 *
 * 后端尚未实现。开发期由 mock 中间件按同一契约供数（见 /mock），
 * **本文件与组件就是最终代码**——后端就绪后只停用 mock，此处一行不改。
 *
 * 三条契约事实（与后端对齐时逐条核对）：
 *   - 「还没填」不是错误 → #54 用 `exists:false`，不用 404；
 *   - 枚举存取分离：库存英文码，中文标签来自 #57 字典，**前端不得硬编码中文**；
 *   - 画像不参与检索、也不落库（08 §2.1/§2.2），前端无需传画像给 stream 接口；
 *     个人解惑的四层注入（画像>指标>会话事实>备忘）全部由服务端组装。
 *   - 画像的"消费出口"是个人解惑会话（kind='health'，见 api/chat.ts #58/#59）——
 *     本文件不再持有任何会话接口（旧"专属对话每用户一条"设计已作废）。
 * ============================================================ */

import { del, get, put, type RequestOptions } from '@/api/request'

/* ---------------- 枚举 ---------------- */

export type Gender = 'male' | 'female' | 'other'
export type Exercise = 'never' | 'rare' | 'weekly1_2' | 'weekly3_5' | 'daily'
export type Smoking = 'never' | 'quit' | 'light' | 'heavy'
export type Drinking = 'never' | 'rarely' | 'social' | 'frequent'

export interface DictOption {
  value: string
  label: string
}

/** 字段字典（契约 #56）：中文标签的唯一来源 */
export interface PortraitOptions {
  genders: DictOption[]
  conditions: DictOption[]
  exercise: DictOption[]
  smoking: DictOption[]
  drinking: DictOption[]
  diet: DictOption[]
  goals: DictOption[]
}

/**
 * 画像的可写字段。
 * 全部可空——表单不该逼着用户填完才能用；填得越多，回答越贴合。
 */
export interface PortraitInput {
  gender: Gender | null
  birthYear: number | null
  heightCm: number | null
  weightKg: number | null
  conditions: string[]
  allergies: string
  medications: string
  exercise: Exercise | null
  sleepHours: number | null
  smoking: Smoking | null
  drinking: Drinking | null
  diet: string[]
  goals: string[]
  note: string
}

export interface Portrait extends PortraitInput {
  /** 是否已填写过（false 时其余字段为 null / 空数组，但接口仍返回 200） */
  exists: boolean
  /** 完善度 0~100，由服务端计算（前端本地实现仅用于即时预览） */
  completeness: number
  filledCount: number
  totalCount: number
  updateTime: string | null
}

/** 未填写的空画像（新建表单的初值，也是"还没填"的渲染依据） */
export function emptyPortrait(): PortraitInput {
  return {
    gender: null,
    birthYear: null,
    heightCm: null,
    weightKg: null,
    conditions: [],
    allergies: '',
    medications: '',
    exercise: null,
    sleepHours: null,
    smoking: null,
    drinking: null,
    diet: [],
    goals: [],
    note: ''
  }
}

/* ---------------- 接口 ---------------- */

/** #54 读取画像。未填写时 exists=false，不是 404 */
export function getPortrait(options?: RequestOptions): Promise<Portrait> {
  return get<Portrait>('/health/portrait', undefined, options)
}

/** #55 保存画像（全量 UPSERT；"清空某字段"因此成为可能） */
export function savePortrait(payload: PortraitInput): Promise<null> {
  return put<null>('/health/portrait', payload)
}

/** #56 清空画像（物理删除；不影响个人解惑会话） */
export function clearPortrait(): Promise<null> {
  return del<null>('/health/portrait')
}

/** #57 字段字典（中文标签唯一来源） */
export function getPortraitOptions(options?: RequestOptions): Promise<PortraitOptions> {
  return get<PortraitOptions>('/health/portrait/options', undefined, options)
}

/* 个人解惑会话（#58/#59）已移至 api/chat.ts —— kind='health' 可多条，
 * 旧"专属对话每用户一条"设计与错误码 2009 作废（11 号 §三）。 */

/* ---------------- 派生纯函数 ----------------
 * 放在 API 层而不是组件里：这些是"口径"，有校验价值、可单测；
 * 组件只负责把它们画出来。
 */

/** 计入完善度的字段总数（与后端 §5.2 的 14 项一致，改动必须两边同步） */
export const PORTRAIT_FIELD_TOTAL = 14

function isBlank(v: unknown): boolean {
  if (v == null) return true
  if (typeof v === 'string') return v.trim() === ''
  if (Array.isArray(v)) return v.length === 0
  return false
}

/**
 * 已填字段数。口径：标量非空即算填；数组非空即算填；文本去空白后非空即算填。
 * （"填了 0 小时睡眠" 与 "没填睡眠" 必须区分——所以用 == null 判空而不是 falsy。）
 */
export function filledCountOf(p: PortraitInput): number {
  return (Object.keys(p) as Array<keyof PortraitInput>).filter((k) => !isBlank(p[k])).length
}

/** 完善度 0~100（前端本地口径；展示以服务端值为准） */
export function completenessOf(p: PortraitInput): number {
  return Math.round((filledCountOf(p) / PORTRAIT_FIELD_TOTAL) * 100)
}

/** 年龄（按当前年份粗算；画像只存出生年，不存生日） */
export function ageOf(birthYear: number | null): number | null {
  if (birthYear == null) return null
  const age = new Date().getFullYear() - birthYear
  return age >= 0 && age <= 130 ? age : null
}

/** BMI = kg / m²，保留 1 位小数 */
export function bmiOf(heightCm: number | null, weightKg: number | null): number | null {
  if (heightCm == null || weightKg == null || heightCm <= 0) return null
  const m = heightCm / 100
  const bmi = weightKg / (m * m)
  return Number.isFinite(bmi) ? Math.round(bmi * 10) / 10 : null
}

export interface BmiBand {
  key: 'thin' | 'normal' | 'over' | 'obese'
  label: string
  /** 指针在四段刻度上的位置 0~100（段中点加权，让指针落在区间中央而非边缘） */
  position: number
  tone: string
}

/** BMI 分段（中国成人标准：<18.5 偏瘦 / 18.5~23.9 正常 / 24~27.9 超重 / ≥28 肥胖） */
export function bmiBand(bmi: number | null): BmiBand | null {
  if (bmi == null) return null
  if (bmi < 18.5) return { key: 'thin', label: '偏瘦', position: 12, tone: 'text-brand' }
  if (bmi < 24) return { key: 'normal', label: '正常', position: 37, tone: 'text-success' }
  if (bmi < 28) return { key: 'over', label: '超重', position: 62, tone: 'text-warning' }
  return { key: 'obese', label: '肥胖', position: 87, tone: 'text-danger' }
}

/** 从字典里取中文标签；取不到时**返回原值**（不自造中文，避免与服务端漂移） */
export function labelOf(list: DictOption[] | undefined, value: string | null): string {
  if (!value) return ''
  return list?.find((o) => o.value === value)?.label ?? value
}

/** 批量取中文标签 */
export function labelsOf(list: DictOption[] | undefined, values: string[]): string[] {
  return values.map((v) => labelOf(list, v))
}

/** 画像是否完全为空（= 不允许创建专属对话的前置条件，对齐 2008） */
export function isPortraitEmpty(p: PortraitInput | Portrait | null): boolean {
  if (!p) return true
  return filledCountOf(p) === 0
}

/**
 * 画像是否超过 `months` 个月未更新（软提示用，08 §7）。
 * 解析失败一律视为"刚更新"——宁可少提示，也不要报错或误报。
 */
export function isStale(updateTime: string | null, months = 6): boolean {
  if (!updateTime) return false
  // 契约格式 yyyy-MM-dd HH:mm:ss（Safari 不认空格分隔，需替换为 T）
  const t = new Date(updateTime.replace(' ', 'T')).getTime()
  if (!Number.isFinite(t)) return false
  const days = (Date.now() - t) / 86_400_000
  return days > months * 30
}

/** 范围校验（与后端 §5.4 的库级 CHECK 同口径）；返回错误文案或 null */
export function validatePortraitField(
  field: 'birthYear' | 'heightCm' | 'weightKg' | 'sleepHours',
  value: number | null
): string | null {
  if (value == null) return null
  if (!Number.isFinite(value)) return '请输入数字'
  switch (field) {
    case 'birthYear':
      return value >= 1900 && value <= 2100 ? null : '出生年份应在 1900~2100 之间'
    case 'heightCm':
      return value >= 80 && value <= 250 ? null : '身高应在 80~250 cm 之间'
    case 'weightKg':
      return value >= 20 && value <= 300 ? null : '体重应在 20~300 kg 之间'
    case 'sleepHours':
      return value >= 0 && value <= 24 ? null : '睡眠时长应在 0~24 小时之间'
  }
}

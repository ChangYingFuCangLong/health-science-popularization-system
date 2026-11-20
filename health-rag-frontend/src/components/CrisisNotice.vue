<script setup lang="ts">
/* ============================================================
 * 危机干预提示（前端设计.md §11.2）
 *
 * 触发：前端本地规则命中（见 stores/chat.ts 的 CRISIS_KEYWORDS）
 * 原则：只提示、不拦截、不隐藏用户输入、不静默改写。
 *       产品可以提示求助渠道，但不能替用户决定"这个问题不该问"。
 *
 * ⚠️ 热线号码必须以卫健部门/官方渠道公布为准，落地前核实；
 *    号码集中配置在下方 HOTLINES 常量，便于更新。
 * ============================================================ */

defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

const HOTLINES: { name: string; tel: string; note: string }[] = [
  {
    name: '全国心理援助热线',
    tel: '12356',
    note: '号码以官方公布为准，接入前核实'
  }
]
</script>

<template>
  <section
    v-if="visible"
    class="msg-in rounded-card border border-warning bg-surface p-4 text-body"
    role="alert"
  >
    <div class="flex items-start justify-between gap-3">
      <div>
        <h2 class="text-section font-medium text-ink">请先照顾好自己</h2>
        <p class="mt-1 text-ink-muted">
          你可以继续提问，系统不会阻止你。但如果你正处于痛苦中，请优先联系下面的渠道——
          它们比任何一次问答都更及时。
        </p>
        <ul class="mt-2 space-y-1">
          <li v-for="line in HOTLINES" :key="line.name">
            <span class="text-ink">{{ line.name }}</span>
            <a :href="`tel:${line.tel}`" class="ml-2 font-medium text-brand-strong underline">
              {{ line.tel }}
            </a>
            <span class="ml-2 text-hint text-ink-muted">{{ line.note }}</span>
          </li>
        </ul>
      </div>

      <button
        type="button"
        class="shrink-0 rounded-btn px-2 py-1 text-hint text-ink-muted hover:bg-canvas"
        aria-label="关闭提示"
        @click="emit('close')"
      >
        关闭
      </button>
    </div>
  </section>
</template>

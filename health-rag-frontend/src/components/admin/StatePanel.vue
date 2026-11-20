<script setup lang="ts">
/* ============================================================
 * 管理端页面的统一状态容器
 *
 * 四种状态优先级：加载中 > 接口未实现 > 故障 > 空数据 > 正常内容。
 * 抽出来的原因：8 个管理端页面都要处理「后端还没实现」这一状态，
 * 各自写一遍必然出现 8 种不同表现。
 * ============================================================ */

const props = withDefaults(
  defineProps<{
    loading?: boolean
    unavailable?: boolean
    errorMsg?: string
    /** 有数据吗（由页面判断，通常是 items.length === 0） */
    empty?: boolean
    /** 接口未实现时展示的说明 */
    pendingTip?: string
    emptyTip?: string
  }>(),
  {
    loading: false,
    unavailable: false,
    errorMsg: '',
    empty: false,
    pendingTip: '该接口后端尚未实现（M8）。页面已按接口文档完成，后端就绪后直接连通。',
    emptyTip: '暂无数据'
  }
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <div>
    <div v-if="props.loading" class="py-10 text-center text-gray-400">加载中…</div>

    <el-empty v-else-if="props.unavailable" :description="props.pendingTip" />

    <el-result
      v-else-if="props.errorMsg"
      icon="warning"
      :title="props.errorMsg"
      sub-title="可重试，或稍后再试"
    >
      <template #extra>
        <el-button type="primary" @click="$emit('retry')">重试</el-button>
      </template>
    </el-result>

    <el-empty v-else-if="props.empty" :description="props.emptyTip" />

    <slot v-else />
  </div>
</template>

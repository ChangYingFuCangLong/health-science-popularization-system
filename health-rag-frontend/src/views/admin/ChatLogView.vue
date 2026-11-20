<script setup lang="ts">
/* 问答日志：列表 + 详情抽屉
 * 依据：接口文档 §六 25（GET /admin/chat-logs）
 * 用途：论文的实验数据来源之一，也是排查回答质量问题的入口 */

import { ref } from 'vue'
import * as adminApi from '@/api/admin'
import type { AdminChatLog } from '@/api/admin'
import { useAdminList } from '@/composer/useAdminList'
import StatePanel from '@/components/admin/StatePanel.vue'

/** 后端【不支持 keyword 搜索】，只支持 userId / isRejected / 日期区间 */
const rejected = ref<number | undefined>(undefined)
const detail = ref<AdminChatLog | null>(null)
const drawer = ref(false)

const list = useAdminList((p) =>
  adminApi.listChatLogs({ ...p, isRejected: rejected.value }, { silent: true })
)

/**
 * el-table 插槽给出的行类型是 DefaultRow（Element Plus 的宽松类型），
 * 这里收窄成 AdminChatLog。若将来后端字段再变，改 AdminChatLog 即可。
 */
function openDetail(row: Record<string, unknown>): void {
  detail.value = row as unknown as AdminChatLog
  drawer.value = true
}

/** 检索策略 → 中文，与消融实验的三种策略对应 */
function modeText(mode: string | null): string {
  if (mode === 'naive') return '向量检索'
  if (mode === 'hybrid') return '混合检索'
  if (mode === 'hybrid_rerank') return '混合+重排'
  return '-'
}
</script>

<template>
  <div>
    <h2 class="mb-4 text-xl font-medium text-gray-800">问答日志</h2>

    <el-card shadow="never" class="mb-4">
      <el-select
        v-model="rejected"
        placeholder="是否拒答"
        clearable
        style="width: 160px"
        @change="list.reload"
      >
        <el-option label="正常回答" :value="0" />
        <el-option label="拒答" :value="1" />
      </el-select>
      <span class="ml-3 text-xs text-gray-400">
        后端暂不支持关键词搜索（仅支持 userId / 是否拒答 / 日期区间）
      </span>
    </el-card>

    <StatePanel
      :loading="list.loading.value"
      :unavailable="list.unavailable.value"
      :error-msg="list.errorMsg.value"
      :empty="list.items.value.length === 0"
      @retry="list.reload"
    >
      <el-table :data="list.items.value" border stripe>
        <el-table-column prop="username" label="用户" width="120" />
        <el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip />
        <!-- F-1：改写后检索词（后端 B-10/M9.5 起产生，历史数据为空） -->
        <el-table-column prop="queryText" label="检索词" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.queryText || '-' }}</template>
        </el-table-column>
        <el-table-column label="策略" width="120">
          <template #default="{ row }">{{ modeText(row.retrieveMode) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">{{ row.latencyMs ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.isRejected === 1" type="info">拒答</el-tag>
            <el-tag v-else-if="row.finishStatus === 0" type="warning">已中断</el-tag>
            <el-tag v-else type="success">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-4 justify-end"
        layout="total, sizes, prev, pager, next"
        :total="list.total.value"
        :current-page="list.pageNum.value"
        :page-size="list.pageSize.value"
        :page-sizes="[10, 20, 50]"
        @current-change="list.onPageChange"
        @size-change="list.onSizeChange"
      />
    </StatePanel>

    <el-drawer v-model="drawer" title="问答详情" size="45%">
      <template v-if="detail">
        <p class="mb-1 text-sm text-gray-500">问题</p>
        <p class="mb-4 text-gray-800">{{ detail.question }}</p>
        <p class="mb-1 text-sm text-gray-500">回答</p>
        <div class="whitespace-pre-wrap text-gray-800">{{ detail.answer }}</div>
        <el-descriptions :column="2" border class="mt-4" size="small">
          <el-descriptions-item label="用户">{{ detail.username }}</el-descriptions-item>
          <el-descriptions-item label="策略">{{ modeText(detail.retrieveMode) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ detail.latencyMs ?? '-' }} ms</el-descriptions-item>
          <el-descriptions-item label="时间">{{ detail.createTime }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </div>
</template>

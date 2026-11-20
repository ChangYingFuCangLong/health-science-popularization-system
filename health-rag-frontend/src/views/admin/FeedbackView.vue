<script setup lang="ts">
/* 反馈管理：列表 + 标记已处理
 * 依据：接口文档 §六 26/27（GET /admin/feedbacks、PUT /admin/feedbacks/{id}） */

import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as adminApi from '@/api/admin'
import { useAdminList } from '@/composer/useAdminList'
import StatePanel from '@/components/admin/StatePanel.vue'

const status = ref<number | undefined>(undefined)

const list = useAdminList((p) =>
  adminApi.listFeedbacks({ ...p, status: status.value }, { silent: true })
)

async function onResolve(rowRaw: Record<string, unknown>): Promise<void> {
  const row = rowRaw as unknown as { id: number }
  try {
    await adminApi.resolveFeedback(row.id)
    ElMessage.success('已标记处理')
    await list.reload()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

function typeText(type: string): string {
  if (type === 'useful') return '有用'
  if (type === 'useless') return '无用'
  if (type === 'wrong') return '内容有误'
  return type ?? '-'
}
</script>

<template>
  <div>
    <h2 class="mb-4 text-xl font-medium text-gray-800">反馈管理</h2>

    <el-card shadow="never" class="mb-4">
      <el-select
        v-model="status"
        placeholder="处理状态"
        clearable
        style="width: 160px"
        @change="list.reload"
      >
        <el-option label="待处理" :value="0" />
        <el-option label="已处理" :value="1" />
      </el-select>
    </el-card>

    <StatePanel
      :loading="list.loading.value"
      :unavailable="list.unavailable.value"
      :error-msg="list.errorMsg.value"
      :empty="list.items.value.length === 0"
      empty-tip="暂无反馈"
      @retry="list.reload"
    >
      <el-table :data="list.items.value" border stripe>
        <el-table-column prop="username" label="用户" width="110" />
        <!-- 后端 FeedbackVO 不返回完整提问与回答，只有回答摘要 answerDigest -->
        <el-table-column
          prop="answerDigest"
          label="被反馈回答（摘要）"
          min-width="240"
          show-overflow-tooltip
        />
        <el-table-column prop="comment" label="补充说明" min-width="160" show-overflow-tooltip />
        <el-table-column label="反馈" width="100">
          <template #default="{ row }">
            <el-tag :type="row.type === 'useful' ? 'success' : 'warning'">
              {{ typeText(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'info' : 'danger'">
              {{ row.status === 1 ? '已处理' : '待处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="row.status === 1"
              @click="onResolve(row)"
            >
              标记处理
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-4 justify-end"
        layout="total, prev, pager, next"
        :total="list.total.value"
        :current-page="list.pageNum.value"
        :page-size="list.pageSize.value"
        @current-change="list.onPageChange"
      />
    </StatePanel>
  </div>
</template>

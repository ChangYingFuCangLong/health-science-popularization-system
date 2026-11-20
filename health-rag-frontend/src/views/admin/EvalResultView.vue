<script setup lang="ts">
/* 实验结果：按策略汇总 + 明细
 * 依据：后端 EvalController §7.4 / §7.5
 *
 * 这两张表是论文「检索策略消融实验」结论的直接出口，
 * 命中率 / MRR / 忠实度保留原始精度展示。 */

import { ref } from 'vue'
import * as adminApi from '@/api/admin'
import { useAdminList, useAdminResource } from '@/composer/useAdminList'
import StatePanel from '@/components/admin/StatePanel.vue'

const strategy = ref<string | undefined>(undefined)

const list = useAdminList((p) =>
  adminApi.listEvalResults({ ...p, strategy: strategy.value }, { silent: true })
)

// 后端返回 { batchNo, items[] }，不是数组
const summary = useAdminResource(() => adminApi.getEvalSummary({}, { silent: true }))

function strategyText(s: string): string {
  if (s === 'naive') return '向量检索'
  if (s === 'hybrid') return '混合检索'
  if (s === 'hybrid_rerank') return '混合+重排'
  return s ?? '-'
}

function pct(v: number | null | undefined): string {
  return v == null ? '-' : (v * 100).toFixed(1) + '%'
}

function num(v: number | null | undefined, digits = 3): string {
  return v == null ? '-' : Number(v).toFixed(digits)
}
</script>

<template>
  <div>
    <h2 class="mb-4 text-xl font-medium text-gray-800">实验结果</h2>

    <el-card shadow="never" class="mb-4">
      <el-select
        v-model="strategy"
        placeholder="检索策略"
        clearable
        style="width: 200px"
        @change="list.reload"
      >
        <el-option label="向量检索（naive）" value="naive" />
        <el-option label="混合检索（hybrid）" value="hybrid" />
        <el-option label="混合+重排（hybrid_rerank）" value="hybrid_rerank" />
      </el-select>
      <span v-if="summary.data.value" class="ml-3 text-xs text-gray-400">
        汇总批次：{{ summary.data.value.batchNo }}
      </span>
    </el-card>

    <h3 class="mb-3 text-base font-medium text-gray-700">策略汇总</h3>
    <StatePanel
      :loading="summary.loading.value"
      :unavailable="summary.unavailable.value"
      :error-msg="summary.errorMsg.value"
      :empty="(summary.data.value?.items ?? []).length === 0"
      empty-tip="暂无汇总数据（需先运行评测）"
      @retry="summary.reload"
    >
      <el-table :data="summary.data.value?.items ?? []" border stripe>
        <el-table-column label="策略" width="140">
          <template #default="{ row }">{{ strategyText(row.strategy) }}</template>
        </el-table-column>
        <el-table-column label="Hit@5" width="110" align="right">
          <template #default="{ row }">{{ pct(row.hitRate5) }}</template>
        </el-table-column>
        <el-table-column label="MRR" width="110" align="right">
          <template #default="{ row }">{{ num(row.mrr) }}</template>
        </el-table-column>
        <el-table-column label="忠实度" width="110" align="right">
          <template #default="{ row }">{{ num(row.avgFaithfulness) }}</template>
        </el-table-column>
        <el-table-column label="平均耗时" width="120" align="right">
          <template #default="{ row }">{{ row.avgLatencyMs ?? '-' }} ms</template>
        </el-table-column>
        <el-table-column prop="sampleCount" label="样本数" width="100" align="right" />
      </el-table>
    </StatePanel>

    <h3 class="mt-8 mb-3 text-base font-medium text-gray-700">明细</h3>
    <StatePanel
      :loading="list.loading.value"
      :unavailable="list.unavailable.value"
      :error-msg="list.errorMsg.value"
      :empty="list.items.value.length === 0"
      empty-tip="暂无实验结果"
      @retry="list.reload"
    >
      <el-table :data="list.items.value" border stripe>
        <el-table-column prop="batchNo" label="批次" width="160" />
        <el-table-column label="策略" width="120">
          <template #default="{ row }">{{ strategyText(row.strategy) }}</template>
        </el-table-column>
        <el-table-column prop="question" label="问题" min-width="240" show-overflow-tooltip />
        <el-table-column label="是否命中" width="100">
          <template #default="{ row }">
            <el-tag :type="row.isHit === 1 ? 'success' : 'info'">
              {{ row.isHit === 1 ? '命中' : '未命中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="命中位次" width="100" align="right">
          <template #default="{ row }">{{ row.hitRank ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="忠实度" width="100" align="right">
          <template #default="{ row }">{{ num(row.faithfulness) }}</template>
        </el-table-column>
        <el-table-column label="引用正确" width="100" align="right">
          <!-- F6：citation_correct 是 0/1 整数（S2 裁决口径），按整数渲染 -->
          <template #default="{ row }">{{ row.citationCorrect ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="耗时(ms)" width="100" align="right">
          <template #default="{ row }">{{ row.latencyMs ?? '-' }}</template>
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

<script setup lang="ts">
/* 评测集管理：列表 + 新增 + 运行评测
 * 依据：接口文档 §七（POST/GET /admin/eval/dataset、POST /admin/eval/run）
 *
 * 这是论文消融实验的入口：同一批评测集 × 三种检索策略 → 对比数据。 */

import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as adminApi from '@/api/admin'
import { useAdminList } from '@/composer/useAdminList'
import StatePanel from '@/components/admin/StatePanel.vue'

const list = useAdminList((p) => adminApi.listEvalDataset(p, { silent: true }))

const dialog = ref(false)
const submitting = ref(false)
// 后端字段是 groundTruth（不是 standardAnswer），另有 refChunkIds / difficulty
const form = reactive({ question: '', groundTruth: '', category: '', difficulty: 1 })

async function onCreate(): Promise<void> {
  if (!form.question.trim() || !form.groundTruth.trim()) {
    ElMessage.warning('问题与标准答案不能为空')
    return
  }
  submitting.value = true
  try {
    await adminApi.createEvalDataset({
      question: form.question.trim(),
      groundTruth: form.groundTruth.trim(),
      category: form.category.trim() || '未分类',
      difficulty: form.difficulty
    })
    ElMessage.success('已新增')
    dialog.value = false
    form.question = ''
    form.groundTruth = ''
    await list.reload()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '新增失败')
  } finally {
    submitting.value = false
  }
}

const runDialog = ref(false)
const running = ref(false)
// 后端入参是 strategies[] + datasetIds[]（可一次跑多策略做对比）
const runForm = reactive({ strategies: ['hybrid'] as string[] })

async function onRun(): Promise<void> {
  if (runForm.strategies.length === 0) {
    ElMessage.warning('至少选择一种检索策略')
    return
  }
  running.value = true
  try {
    await adminApi.runEval({ strategies: runForm.strategies })
    ElMessage.success('评测已提交，结果见「实验结果」')
    runDialog.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '评测失败')
  } finally {
    running.value = false
  }
}
</script>

<template>
  <div>
    <h2 class="mb-4 text-xl font-medium text-gray-800">评测集管理</h2>

    <el-card shadow="never" class="mb-4">
      <el-button type="primary" @click="dialog = true">新增题目</el-button>
      <el-button @click="runDialog = true">运行评测</el-button>
      <el-button @click="list.reload">刷新</el-button>
    </el-card>

    <StatePanel
      :loading="list.loading.value"
      :unavailable="list.unavailable.value"
      :error-msg="list.errorMsg.value"
      :empty="list.items.value.length === 0"
      empty-tip="暂无评测题目"
      @retry="list.reload"
    >
      <el-table :data="list.items.value" border stripe>
        <el-table-column prop="category" label="分类" width="120" />
        <el-table-column prop="question" label="问题" min-width="280" show-overflow-tooltip />
        <el-table-column label="难度" width="80" align="right">
          <template #default="{ row }">{{ row.difficulty ?? '-' }}</template>
        </el-table-column>
        <el-table-column
          prop="groundTruth"
          label="标准答案"
          min-width="280"
          show-overflow-tooltip
        />
        <el-table-column prop="createTime" label="创建时间" width="170" />
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

    <el-dialog v-model="dialog" title="新增评测题目" width="560px">
      <el-form label-width="90px">
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="如：疾病防治" />
        </el-form-item>
        <el-form-item label="问题">
          <el-input v-model="form.question" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="标准答案">
          <el-input v-model="form.groundTruth" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="难度">
          <el-input-number v-model="form.difficulty" :min="1" :max="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onCreate">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="runDialog" title="运行评测" width="460px">
      <el-form label-width="90px">
        <el-form-item label="检索策略">
          <el-select v-model="runForm.strategies" multiple style="width: 100%">
            <el-option label="向量检索（naive）" value="naive" />
            <el-option label="混合检索（hybrid）" value="hybrid" />
            <el-option label="混合+重排（hybrid_rerank）" value="hybrid_rerank" />
          </el-select>
        </el-form-item>
        <div class="text-xs text-gray-400">可多选，一次跑完即可横向对比策略</div>
      </el-form>
      <template #footer>
        <el-button @click="runDialog = false">取消</el-button>
        <el-button type="primary" :loading="running" @click="onRun">开始</el-button>
      </template>
    </el-dialog>
  </div>
</template>

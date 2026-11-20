<script setup lang="ts">
/* 语料管理：上传 / 列表 / 重建 / 删除 / 查看分块
 * 依据：接口文档 §六 6.1~6.5
 *
 * 上传（F2）：el-upload http-request 自定义提交（带 Authorization 头，multipart）。
 * 客户端预检扩展名白名单 + 20MB；受理后按 §6.3 以 2s 轮询进度，
 * status=2 停止并刷新列表，status=3 展示 failReason。
 * 文档状态枚举（02 §6.1）：0 待处理 / 1 处理中 / 2 已完成 / 3 失败。
 */

import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as adminApi from '@/api/admin'
import { UPLOAD_ACCEPT, UPLOAD_TYPES_LABEL, validateUploadFile } from '@/constants/upload'
import { useAdminList } from '@/composer/useAdminList'
import StatePanel from '@/components/admin/StatePanel.vue'

const router = useRouter()
const keyword = ref('')

const list = useAdminList((p) =>
  adminApi.listDocuments(
    { ...p, keyword: keyword.value.trim() || undefined },
    { silent: true }
  )
)

async function onRebuild(rowRaw: Record<string, unknown>): Promise<void> {
  const row = rowRaw as unknown as { id: number }
  try {
    const res = await adminApi.rebuildDocument(row.id)
    ElMessage.success(
      (res as { message?: string } | null)?.message ?? '重建任务已提交，后台异步执行'
    )
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重建失败')
  }
}

const rebuildingAll = ref(false)

async function onRebuildAll(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将全量重建向量索引（重新向量化所有分块，耗时较长且会消耗 embedding 额度）。确定继续？',
      '重建全量索引',
      { type: 'warning' }
    )
  } catch {
    return
  }
  rebuildingAll.value = true
  try {
    const res = await adminApi.rebuildVectors()
    ElMessage.success(
      (res as { message?: string } | null)?.message ?? '重建任务已提交，后台异步执行'
    )
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重建失败')
  } finally {
    rebuildingAll.value = false
  }
}

async function onDelete(rowRaw: Record<string, unknown>): Promise<void> {
  const row = rowRaw as unknown as { id: number; title: string }
  try {
    await ElMessageBox.confirm(`确定删除《${row.title}》？其分块与向量会一并移除。`, '删除确认', {
      type: 'warning'
    })
  } catch {
    return // 用户取消
  }
  try {
    await adminApi.deleteDocument(row.id)
    ElMessage.success('已删除')
    await list.reload()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function statusType(status: number): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 2) return 'success' // 已完成
  if (status === 1) return 'warning' // 处理中
  if (status === 0) return 'info' // 待处理
  return 'danger' // 3 失败
}

function statusText(status: number): string {
  if (status === 2) return '已完成'
  if (status === 1) return '处理中'
  if (status === 0) return '待处理'
  return '失败'
}

/* ---------------- 上传（F2：02 §6.2 / §6.3） ---------------- */

const uploading = ref(false)
const progressText = ref('')
let pollTimer: number | null = null
let pollingDocId: number | null = null

function stopPolling(): void {
  if (pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  pollingDocId = null
}

/** 受理后 2s 轮询：2 完成 → 刷新列表；3 失败 → 展示 failReason；单次轮询失败静默等下一轮 */
function startPolling(documentId: number): void {
  stopPolling()
  pollingDocId = documentId
  progressText.value = '解析中…'
  pollTimer = window.setInterval(async () => {
    if (pollingDocId == null) return
    try {
      const p = await adminApi.getDocumentProgress(pollingDocId, { silent: true })
      if (p.status === 2) {
        stopPolling()
        uploading.value = false
        progressText.value = ''
        ElMessage.success('解析完成，已可被问答引用')
        await list.reload()
      } else if (p.status === 3) {
        stopPolling()
        uploading.value = false
        progressText.value = ''
        ElMessage.error(`解析失败：${p.failReason || '未知原因'}`)
        await list.reload()
      } else {
        progressText.value =
          p.chunksTotal > 0 ? `解析中 ${p.chunksDone}/${p.chunksTotal}` : '解析中…'
      }
    } catch {
      /* 单次失败忽略：可能是网络抖动，下一轮继续 */
    }
  }, 2000)
}

/** 扩展名白名单 + 20MB 预检（契约 §6.2）；返回 false 阻止上传 */
/** 预检白名单与后端 upload-allowed-types 同源（@/constants/upload），不再本地写字面量 */
function beforeUpload(file: File): boolean {
  const problem = validateUploadFile(file)
  if (problem) {
    ElMessage.warning(problem || `仅支持 ${UPLOAD_TYPES_LABEL}`)
    return false
  }
  return true
}

async function onUploadRequest(options: { file: File }): Promise<void> {
  uploading.value = true
  try {
    const res = await adminApi.uploadDocument(options.file)
    ElMessage.success(res.message || '已受理，正在解析')
    startPolling(res.documentId)
  } catch {
    /* 错误提示已由拦截器弹出 */
    uploading.value = false
    progressText.value = ''
  }
}

onBeforeUnmount(stopPolling)
</script>

<template>
  <div>
    <h2 class="mb-4 text-xl font-medium text-gray-800">语料管理</h2>

    <el-card shadow="never" class="mb-4">
      <div class="flex flex-wrap items-center gap-3">
        <el-input
          v-model="keyword"
          placeholder="按标题搜索"
          clearable
          style="width: 220px"
          @keyup.enter="list.reload"
          @clear="list.reload"
        />
        <!-- F2：后端上传+解析链路已就绪（02 §6.2/§6.3）；受理后轮询进度，完成自动刷新 -->
        <el-upload
          :show-file-list="false"
          :accept="UPLOAD_ACCEPT"
          :before-upload="beforeUpload"
          :http-request="onUploadRequest"
        >
          <el-button type="primary" :loading="uploading && !progressText">上传文档</el-button>
        </el-upload>
        <span v-if="progressText" class="text-xs text-gray-400">{{ progressText }}</span>
        <el-button @click="onRebuildAll" :loading="rebuildingAll">重建全量索引</el-button>
        <el-button @click="list.reload">刷新</el-button>
      </div>
    </el-card>

    <StatePanel
      :loading="list.loading.value"
      :unavailable="list.unavailable.value"
      :error-msg="list.errorMsg.value"
      :empty="list.items.value.length === 0"
      empty-tip="暂无语料，可先上传文档"
      @retry="list.reload"
    >
      <el-table :data="list.items.value" border stripe>
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="120" />
        <el-table-column prop="source" label="来源" width="160" show-overflow-tooltip />
        <el-table-column label="原文" width="72" align="center">
          <template #default="{ row }">
            <a
              v-if="row.sourceUrl"
              :href="row.sourceUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="text-primary hover:underline"
              :title="row.sourceUrl"
            >链接</a>
            <span v-else class="text-[var(--el-text-color-placeholder)]" title="早期 seed 批次无采集记录，无法溯源">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分块数" width="90" align="right" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="上传时间" width="170" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/admin/documents/${row.id}/chunks`)">
              分块
            </el-button>
            <el-button link type="primary" @click="onRebuild(row)">重建</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
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
  </div>
</template>

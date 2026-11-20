<script setup lang="ts">
/* 分块查看：某篇文档的分块列表
 * 依据：后端 AdminController §6.5（GET /admin/documents/{id}/chunks）
 *
 * ⚠️ 该接口返回的是【数组】而不是分页对象（接口文档写的是分页），
 * 因此这里不走 useAdminList，直接取数组。 */

import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as adminApi from '@/api/admin'
import { useAdminResource } from '@/composer/useAdminList'
import StatePanel from '@/components/admin/StatePanel.vue'

const route = useRoute()
const router = useRouter()

const docId = computed(() => {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(id) && id > 0 ? id : null
})

const chunks = useAdminResource(() => adminApi.listChunks(docId.value as number, { silent: true }))
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <h2 class="text-xl font-medium text-gray-800">分块查看</h2>
      <el-button @click="router.push('/admin/documents')">返回语料管理</el-button>
    </div>

    <el-alert v-if="docId === null" type="warning" title="缺少文档 ID" :closable="false" />

    <StatePanel
      v-else
      :loading="chunks.loading.value"
      :unavailable="chunks.unavailable.value"
      :error-msg="chunks.errorMsg.value"
      :empty="(chunks.data.value ?? []).length === 0"
      empty-tip="该文档暂无分块"
      @retry="chunks.reload"
    >
      <el-table :data="chunks.data.value ?? []" border stripe>
        <el-table-column prop="chunkIndex" label="序号" width="80" align="right" />
        <el-table-column prop="charCount" label="字数" width="90" align="right" />
        <el-table-column prop="content" label="内容" min-width="480" show-overflow-tooltip />
      </el-table>
    </StatePanel>
  </div>
</template>

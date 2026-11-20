<script setup lang="ts">
/* 用户管理：列表 + 启用/禁用
 * 依据：接口文档 §六 28/29（GET /admin/users、PUT /admin/users/{id}/status）
 *
 * 注意：逻辑删除的账号在列表里由后端过滤（deleted=0），
 * 用户名唯一性靠「删除时改写 username」保证（设计定稿 §3.2 决策）。 */

import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as adminApi from '@/api/admin'
import { useAdminList } from '@/composer/useAdminList'
import StatePanel from '@/components/admin/StatePanel.vue'

// F1：后端已支持 keyword（用户名/昵称模糊匹配），空串等价于全量分页
const keyword = ref('')
const list = useAdminList((p) =>
  adminApi.listUsers({ ...p, keyword: keyword.value.trim() || undefined }, { silent: true })
)

function search(): void {
  list.pageNum.value = 1
  void list.reload()
}

async function toggleStatus(rowRaw: Record<string, unknown>): Promise<void> {
  const row = rowRaw as unknown as { id: number; username: string; status: number }
  const next = row.status === 1 ? 0 : 1
  const action = next === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定${action}用户「${row.username}」？`, `${action}确认`, {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await adminApi.setUserStatus(row.id, next)
    ElMessage.success(`已${action}`)
    await list.reload()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : `${action}失败`)
  }
}

async function deleteUser(rowRaw: Record<string, unknown>): Promise<void> {
  const row = rowRaw as unknown as { id: number; username: string }
  try {
    await ElMessageBox.confirm(
      `确定删除用户「${row.username}」？其健康数据与资料库将一并清除，且不可恢复。`,
      '删除用户',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await adminApi.deleteUser(row.id)
    ElMessage.success('已删除')
    await list.reload()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function roleText(role: string): string {
  return role === 'ADMIN' ? '管理员' : '普通用户'
}
</script>

<template>
  <div>
    <h2 class="mb-4 text-xl font-medium text-gray-800">用户管理</h2>

    <el-card shadow="never" class="mb-4">
      <div class="flex flex-wrap items-center gap-3">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名或昵称"
          clearable
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" @click="search">搜索</el-button>
        <el-button @click="list.reload">刷新</el-button>
        <span class="text-xs text-gray-400">支持用户名、昵称模糊匹配；清空后显示全部</span>
      </div>
    </el-card>

    <StatePanel
      :loading="list.loading.value"
      :unavailable="list.unavailable.value"
      :error-msg="list.errorMsg.value"
      :empty="list.items.value.length === 0"
      @retry="list.reload"
    >
      <el-table :data="list.items.value" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column prop="nickname" label="昵称" width="120" />
        <el-table-column prop="email" label="邮箱" width="180" show-overflow-tooltip />
        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'">
              {{ roleText(row.role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最近登录" width="170" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="注册时间" width="170" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              :type="row.status === 1 ? 'danger' : 'primary'"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="deleteUser(row)">删除</el-button>
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

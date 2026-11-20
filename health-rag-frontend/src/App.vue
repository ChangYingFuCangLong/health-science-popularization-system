<script setup lang="ts">
/* Element Plus 的中文语言包。
 * 不配 locale 时，组件内置文案全是英文：分页器显示 "Total 26"、确认框按钮是
 * Cancel / OK。这里只在根组件包一层 ElConfigProvider，不引入全量 Element Plus，
 * 因此按需加载策略不受影响（ElConfigProvider 由 unplugin-vue-components 自动导入）。 */
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import AppBackground from '@/components/AppBackground.vue'
import ToastHost from '@/components/ToastHost.vue'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <AppBackground />
    <router-view />
    <ToastHost />
  </el-config-provider>
</template>

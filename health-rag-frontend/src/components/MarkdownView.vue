<script setup lang="ts">
/* ============================================================
 * Markdown 渲染（前端设计.md §1）
 *
 * 两条硬约束：
 *   1. XSS：html: false —— 回答内容含用户输入与语料，不可信任（系统设计.md §9）
 *   2. 体积：highlight.js 只注册用到的语言。
 *      全语言包 ≈ 900KB，子集约 30KB（前端设计.md §9.2 措施 3）
 * ============================================================ */

import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import json from 'highlight.js/lib/languages/json'
import bash from 'highlight.js/lib/languages/bash'
import python from 'highlight.js/lib/languages/python'
import java from 'highlight.js/lib/languages/java'
import sql from 'highlight.js/lib/languages/sql'
import xml from 'highlight.js/lib/languages/xml'

hljs.registerLanguage('json', json)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('python', python)
hljs.registerLanguage('java', java)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('xml', xml)

const props = defineProps<{ content: string }>()

const md = new MarkdownIt({
  // 不渲染原始 HTML —— 这是本组件的 XSS 防线
  html: false,
  linkify: true,
  breaks: true,
  highlight(code: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value
      } catch {
        // 高亮失败时不阻断渲染，回落到转义后的纯文本
      }
    }
    return ''
  }
})

const html = computed(() => md.render(props.content ?? ''))
</script>

<template>
  <div class="markdown-body" v-html="html" />
</template>

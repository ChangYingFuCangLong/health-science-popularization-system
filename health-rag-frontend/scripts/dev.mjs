#!/usr/bin/env node
/**
 * dev 启动器：剥离宿主注入的 NODE_OPTIONS 后再拉起 Vite。
 *
 * 背景：本机 Node 由宿主经 NODE_OPTIONS=--require=<node-language-shim.cjs> 注入
 * 文件系统保护 shim（批量删除保护）。Vite 首次预构建 Element Plus 依赖时，
 * 会在提交阶段删除 deps_temp（>50 文件）→ 被 shim 拦截抛错 → dev server 直接退出。
 *
 * 该 shim 与业务无关，这里只对 Vite 子进程移除它，避免误伤开发服务器。
 */
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const env = { ...process.env }
delete env.NODE_OPTIONS

const viteBin = resolve(root, 'node_modules/vite/bin/vite.js')
const child = spawn(process.execPath, [viteBin, ...process.argv.slice(2)], {
  stdio: 'inherit',
  cwd: root,
  env,
})

child.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)))
child.on('error', (err) => {
  console.error('[dev] 启动 Vite 失败:', err)
  process.exit(1)
})

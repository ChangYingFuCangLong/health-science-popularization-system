/* ============================================================
 * 契约 mock · Vite 开发中间件插件
 *
 * 为什么放在 Vite 中间件层，而不是 axios adapter 或组件内硬编码：
 *   1. mock 代码**完全不进浏览器产物**——它只在 Node 侧被 vite.config.ts 引用；
 *   2. 组件与 `src/api/*.ts` 一行都不用改，**它们就是最终代码**；
 *   3. 后端就绪后无需删除任何业务代码，本插件会自动让路（见下）。
 *
 * 「后端优先」策略：每个请求先做一次 TCP 探活（结果缓存 3 秒）。
 *   后端在线 → 直接 next()，交给 Vite 原生 proxy 转发，行为与没有本插件完全一致；
 *   后端离线 → 命中 mock。
 * 即：**装了插件也不影响联调**，不需要手动开关。
 * 需要强制指定时用环境变量：VITE_MOCK_API=on | off。
 *
 * 落地顺序（关键）：configureServer 内部直接调用 server.middlewares.use()，
 * 此时 Vite 的 proxy 中间件尚未安装，因此本插件天然先于 proxy 执行——
 * 这正是「后端优先」能成立的前提。
 * ⚠️ 绝不能写成 `return () => server.middlewares.use(...)`：那是 post hook，
 *    会排在 proxy 之后，全部 /api 请求已被 proxy 吃掉，mock 永远不生效。
 * ============================================================ */

import net from 'node:net'
import type { Connect, Plugin } from 'vite'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { resetRoute, route } from './routes'

const API_PREFIX = '/api/'
const PROBE_HOST = '127.0.0.1'
const PROBE_PORT = Number(process.env.MOCK_PROBE_PORT ?? 8080)
const PROBE_TIMEOUT_MS = 250
const PROBE_CACHE_MS = 3000

type Mode = 'auto' | 'on' | 'off'

let probeCache = { at: 0, up: false }

function probeBackend(): Promise<boolean> {
  const now = Date.now()
  if (now - probeCache.at < PROBE_CACHE_MS) return Promise.resolve(probeCache.up)

  return new Promise<boolean>((resolve) => {
    const socket = new net.Socket()
    let settled = false
    const finish = (up: boolean) => {
      if (settled) return
      settled = true
      socket.destroy()
      probeCache = { at: Date.now(), up }
      resolve(up)
    }
    socket.setTimeout(PROBE_TIMEOUT_MS)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
    socket.connect(PROBE_PORT, PROBE_HOST)
  })
}

async function readBody(req: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of req) {
    chunks.push(chunk as Buffer)
  }
  return Buffer.concat(chunks).toString('utf8')
}

interface Logger {
  info(msg: string): void
  error(msg: string): void
}

function createMiddleware(mode: Mode, logger: Logger): Connect.NextHandleFunction {
  return (req, res, next) => {
    void (async () => {
      const url = req.url ?? ''
      // 只接管 /api/*：Vite 自身的模块请求（/src、/@fs、@vite/client）一律放行，
      // 否则会把源码模块也拦下来，应用直接挂不起来（纯白屏）
      if (!url.startsWith(API_PREFIX)) return next()
      if (mode === 'off') return next()

      try {
        if (mode === 'auto' && (await probeBackend())) return next()

        const parsed = new URL(url, 'http://localhost')
        const method = req.method ?? 'GET'
        const raw = method === 'GET' || method === 'HEAD' ? '' : await readBody(req)

        // 重置端点（仅 mock）：验收脚本用它保证每次都从同一初始态出发
        if (resetRoute(method, parsed.pathname)) {
          res.statusCode = 200
          res.setHeader('Content-Type', 'application/json; charset=utf-8')
          res.end(JSON.stringify({ code: 200, message: 'mock 内存态已重置', data: null }))
          logger.info('  \u001b[35m[mock]\u001b[0m 内存态已重置')
          return
        }

        const result = route(method, parsed.pathname, parsed.searchParams, raw)

        if (result.kind === 'sse') {
          req.on('close', () => res.end())
          result.write(res as ServerResponse)
          return
        }

        const code = (result.body as { code?: number }).code ?? 0
        res.statusCode = 200
        res.setHeader('Content-Type', 'application/json; charset=utf-8')
        res.setHeader('X-Mock-Api', '1')
        res.end(JSON.stringify(result.body))
        logger.info(`  \u001b[35m[mock]\u001b[0m ${method} ${parsed.pathname} → code ${code}`)
      } catch (e) {
        logger.error(`[mock] 处理失败：${String(e)}`)
        next(e)
      }
    })()
  }
}

export function mockApiPlugin(): Plugin {
  const raw = (process.env.VITE_MOCK_API ?? 'auto').toLowerCase()
  const mode: Mode = raw === 'on' || raw === 'off' ? raw : 'auto'

  return {
    name: 'contract-mock-api',
    // 只在 dev server 生效，生产构建完全不引入本模块
    apply: 'serve',

    configureServer(server) {
      const logger = {
        info: (m: string) => server.config.logger.info(m),
        error: (m: string) => server.config.logger.error(m)
      }
      server.middlewares.use(createMiddleware(mode, logger))

      const label =
        mode === 'off'
          ? '已关闭（VITE_MOCK_API=off）'
          : mode === 'on'
            ? '强制启用（VITE_MOCK_API=on）'
            : `自动（后端优先，探活 ${PROBE_HOST}:${PROBE_PORT}）`
      logger.info(`  \u001b[36m➜\u001b[0m  契约 mock：${label}`)
    }
  }
}

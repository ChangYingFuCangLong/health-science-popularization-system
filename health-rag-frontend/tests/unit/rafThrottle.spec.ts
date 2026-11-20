// @vitest-environment node
// ADR-016：合帧节流。注入手动调度器，避免依赖 requestAnimationFrame
import { describe, expect, it, vi } from 'vitest'
import { createRafThrottle } from '@/utils/rafThrottle'

function manualScheduler() {
  let queued: (() => void) | null = null
  return {
    schedule: (cb: () => void): number => {
      queued = cb
      return 1
    },
    cancel: (): void => {
      queued = null
    },
    run: (): void => {
      const cb = queued
      queued = null
      cb?.()
    }
  }
}

describe('createRafThrottle', () => {
  it('一帧内的多次 push 合并成一次写入', () => {
    const s = manualScheduler()
    const applied: string[][] = []
    const t = createRafThrottle<string>((items) => applied.push([...items]), {
      schedule: s.schedule,
      cancel: s.cancel
    })

    t.push('a')
    t.push('b')
    t.push('c')

    // 还没到帧：不应触发写入
    expect(applied).toEqual([])
    expect(t.pendingCount()).toBe(3)

    s.run()

    expect(applied).toEqual([['a', 'b', 'c']])
    expect(t.pendingCount()).toBe(0)
  })

  it('flush() 立即提交，不等下一帧（done 时必须调用，否则末尾字符会丢）', () => {
    const s = manualScheduler()
    const applied: string[][] = []
    const t = createRafThrottle<string>((items) => applied.push([...items]), {
      schedule: s.schedule,
      cancel: s.cancel
    })

    t.push('最后几个字')
    t.flush()

    expect(applied).toEqual([['最后几个字']])
    expect(t.pendingCount()).toBe(0)
  })

  it('flush() 会取消已排队但未执行的帧，不会重复提交', () => {
    const s = manualScheduler()
    const applied: string[][] = []
    const t = createRafThrottle<string>((items) => applied.push([...items]), {
      schedule: s.schedule,
      cancel: s.cancel
    })

    t.push('x')
    t.flush() // 提交并取消排队
    s.run() // 此时队列已空，不应再触发

    expect(applied).toEqual([['x']])
  })

  it('没有待处理项时不会调用 apply', () => {
    const s = manualScheduler()
    const apply = vi.fn()
    const t = createRafThrottle<string>(apply, { schedule: s.schedule, cancel: s.cancel })

    t.flush()
    s.run()

    expect(apply).not.toHaveBeenCalled()
  })

  it('多帧场景：每帧各提交一次', () => {
    const s = manualScheduler()
    const applied: string[][] = []
    const t = createRafThrottle<string>((items) => applied.push([...items]), {
      schedule: s.schedule,
      cancel: s.cancel
    })

    t.push('1')
    s.run()
    t.push('2')
    t.push('3')
    s.run()

    expect(applied).toEqual([['1'], ['2', '3']])
  })
})

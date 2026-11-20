/* ============================================================
 * 系统脉搏（Vital 签名动效）
 *
 * 顶栏的心电图脉搏线是「系统正在工作」的具象化：
 * 流式生成期间脉搏加速发亮（live），空闲时缓动呼吸（idle）。
 * 模块级单例 ref —— chat store 写入，布局层读取，不经过 props 逐层传。
 * ============================================================ */

import { ref } from 'vue'

const live = ref(false)

export function usePulse() {
  return {
    live,
    setLive(v: boolean): void {
      live.value = v
    }
  }
}

/* ============================================================
 * localStorage 兼容读取
 *
 * 背景：历史版本用 `qian-` 前缀存偏好（主题、自动朗读、健康指标）。
 * 工程更名为 health-rag-* 后，旧键第一次读取时自动迁移到新键，
 * 用户已有的主题与偏好不会丢失。
 * ============================================================ */

export function readWithLegacy(key: string, legacyKey: string): string | null {
  try {
    const current = localStorage.getItem(key)
    if (current !== null) return current
    const legacy = localStorage.getItem(legacyKey)
    if (legacy === null) return null
    localStorage.setItem(key, legacy)
    localStorage.removeItem(legacyKey)
    return legacy
  } catch {
    return null
  }
}

export function migrateKey(key: string, legacyKey: string): void {
  readWithLegacy(key, legacyKey)
}

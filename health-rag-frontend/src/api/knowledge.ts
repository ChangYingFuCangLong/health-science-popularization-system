import type { PageResult } from '@/api/types'
import { get } from '@/api/request'
import type { RequestOptions } from '@/api/request'

export interface KnowledgeCategory {
  name: string
  count: number
}

/**
 * 字段以后端实际返回为准（2026-09-18 核对 `/api/knowledge/articles`）：
 * id / title / source / category / chunkCount / createTime
 * —— 注意**没有 summary**，早期按设计文档写成了 summary，与实际不符。
 */
export interface KnowledgeArticle {
  id: number
  title: string
  category: string
  source: string
  /** 该文档被切成多少块（正文由 chunk 拼接还原，接口不返回整篇原文） */
  chunkCount: number
  createTime: string
}

export interface ArticleDetail {
  id: number
  title: string
  source: string
  category: string
  /** 原文出处，后端从首块 meta_json 取；可能为空 */
  url?: string
  chunkCount?: number
  content: string
  /**
   * V2（02-接口文档 §5.3）：按章节分组返回（结构化分块文档有效；
   * V1 历史文档整体归入「正文」组）。content 全文始终保留。
   */
  sections?: Array<{ title: string; content: string }> | null
}

/** 相关文章（02-接口文档 §5.4，V2 M12 pgvector 质心近邻） */
export interface RelatedArticle {
  id: number
  title: string
  category: string
  /** 相似度 0~1 */
  similarity: number
}

export interface ListArticlesParams {
  category?: string
  keyword?: string
  pageNum?: number
  pageSize?: number
}

/**
 * 说明：知识浏览属于 M5（语料入库）之后才有的能力。
 * 在此之前后端返回 404，因此这些调用一律传 `silent: true`——
 * 由页面自行降级为空态，而不是用全局错误提示刷屏。
 */
export function listCategories(options?: RequestOptions): Promise<KnowledgeCategory[]> {
  return get<KnowledgeCategory[]>('/knowledge/categories', undefined, options)
}

export function listArticles(
  params: ListArticlesParams = {},
  options?: RequestOptions
): Promise<PageResult<KnowledgeArticle>> {
  return get<PageResult<KnowledgeArticle>>('/knowledge/articles', params, options)
}

export function getArticle(id: number, options?: RequestOptions): Promise<ArticleDetail> {
  return get<ArticleDetail>(`/knowledge/articles/${id}`, undefined, options)
}

/**
 * 相关文章推荐（V2 M12）：pgvector 以本文档分块质心做近邻，排除本文档。
 * 未实现（404）或无向量时返回空/失败——前端回退「同分类」客户端派生。
 */
export function listRelatedArticles(
  id: number,
  limit = 5,
  options?: RequestOptions
): Promise<{ articles: RelatedArticle[] }> {
  return get<{ articles: RelatedArticle[] }>(
    `/knowledge/articles/${id}/related`,
    { limit },
    options
  )
}

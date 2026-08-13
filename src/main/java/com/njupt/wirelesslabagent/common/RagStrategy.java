package com.njupt.wirelesslabagent.common;

/**
 * RAG 检索策略：控制请求走哪条 Advisor 链。
 */
public enum RagStrategy {
    /** 无 RAG：纯对话 */
    NONE,
    /** 单查询：用户原始问题直接检索 */
    SINGLE,
    /** 查询重写：LLM 将模糊问题重写为结构化查询后检索 */
    REWRITE,
    /** 多查询扩展：LLM 扩写 N 个变体 → 多路检索去重 */
    MULTI,
    /** 查询翻译：LLM 翻译查询后检索，适用跨语言场景 */
    TRANSLATE,
    /** 查询压缩：对话历史压缩为独立查询后检索，解决多轮追问歧义 */
    COMPRESS
}

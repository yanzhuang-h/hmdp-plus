import request from '@/utils/request'

/**
 * 智能客服对话。
 *
 * 后端接口：POST /ai/chat?message=xxx&sessionId=xxx
 * - 同一 sessionId 内保持多轮上下文
 * - 需要登录（后端会从登录态注入用户身份，用于订单查询的越权校验）
 *
 * @param {string} message   用户问题
 * @param {string} sessionId 会话标识
 */
export const chatWithAi = (message, sessionId) =>
  request.post('/ai/chat', null, { params: { message, sessionId } })

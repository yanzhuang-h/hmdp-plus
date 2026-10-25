<script setup>
import { ref, nextTick } from 'vue'
import { ChatDotRound, Close } from '@element-plus/icons-vue'
import { chatWithAi } from '@/api/ai'
import { useUserStore } from '@/stores'

const userStore = useUserStore()

const visible = ref(false)
const input = ref('')
const loading = ref(false)
const listRef = ref(null)

const SESSION_KEY = 'hmdp-ai-session'

const messages = ref([
  {
    role: 'assistant',
    content: '你好，我是黑马点评智能客服。可以帮你查商铺、优惠券和订单状态，试试问我「1号商铺有什么券」。'
  }
])

function newSessionId() {
  const id = `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  localStorage.setItem(SESSION_KEY, id)
  return id
}

const sessionId = ref(localStorage.getItem(SESSION_KEY) || newSessionId())

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return

  if (!userStore.token) {
    messages.value.push({
      role: 'assistant',
      content: '请先登录后再使用智能客服。',
      error: true
    })
    scrollToBottom()
    return
  }

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true
  scrollToBottom()

  try {
    const res = await chatWithAi(text, sessionId.value)
    if (res.success) {
      messages.value.push({ role: 'assistant', content: res.data })
    } else {
      messages.value.push({
        role: 'assistant',
        content: res.errorMsg || '抱歉，服务暂时不可用',
        error: true
      })
    }
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      content: '请求失败，请稍后重试',
      error: true
    })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

function clearChat() {
  messages.value = [{ role: 'assistant', content: '会话已清空，有什么可以帮你？' }]
  sessionId.value = newSessionId()
}
</script>

<template>
  <!-- 悬浮入口 -->
  <div v-if="!visible" class="ai-fab" @click="visible = true" title="智能客服">
    <el-icon :size="21"><ChatDotRound /></el-icon>
  </div>

  <!-- 对话面板 -->
  <div v-else class="ai-panel">
    <header class="ai-header">
      <div class="ai-title">
        <el-icon><ChatDotRound /></el-icon>
        <span>智能客服</span>
      </div>
      <div class="ai-actions">
        <el-button link size="small" @click="clearChat">新会话</el-button>
        <el-button link size="small" @click="visible = false">
          <el-icon><Close /></el-icon>
        </el-button>
      </div>
    </header>

    <div ref="listRef" class="ai-messages">
      <div
        v-for="(m, i) in messages"
        :key="i"
        :class="['ai-msg', m.role, { 'is-error': m.error }]"
      >
        <div class="ai-bubble">{{ m.content }}</div>
      </div>
      <div v-if="loading" class="ai-msg assistant">
        <div class="ai-bubble typing">
          <span></span><span></span><span></span>
        </div>
      </div>
    </div>

    <footer class="ai-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="问点什么，比如：1号商铺有什么优惠券？"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :loading="loading" class="ai-send" @click="send">
        发送
      </el-button>
    </footer>
  </div>
</template>

<style scoped>
.ai-fab {
  position: fixed;
  right: 16px;
  bottom: 96px;
  z-index: 2000;
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--c-primary);
  color: var(--c-accent);
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(4, 60, 117, 0.26);
  transition: transform 0.2s, box-shadow 0.2s;
}
.ai-fab:active {
  transform: scale(0.92);
}

.ai-panel {
  position: fixed;
  right: 12px;
  bottom: 96px;
  z-index: 2000;
  width: 369px;
  height: 540px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-float);
  overflow: hidden;
}

.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--c-primary);
  color: #fff;
}
.ai-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
}
.ai-actions :deep(.el-button) {
  color: rgba(255, 255, 255, 0.9);
}
.ai-actions :deep(.el-button:hover) {
  color: #fff;
}

.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: var(--c-bg);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ai-msg {
  display: flex;
}
.ai-msg.user {
  justify-content: flex-end;
}
.ai-msg.assistant {
  justify-content: flex-start;
}

.ai-bubble {
  max-width: 78%;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-msg.assistant .ai-bubble {
  background: #fff;
  color: var(--c-text);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.ai-msg.user .ai-bubble {
  background: var(--c-primary);
  color: #fff;
}
.ai-msg.is-error .ai-bubble {
  background: #fff1f0;
  color: #d4380d;
  box-shadow: inset 0 0 0 1px #ffccc7;
}

.ai-bubble.typing {
  display: flex;
  gap: 4px;
  padding: 14px 16px;
}
.ai-bubble.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-border-strong);
  animation: blink 1.4s infinite both;
}
.ai-bubble.typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.ai-bubble.typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.2;
  }
  40% {
    opacity: 1;
  }
}

.ai-input {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--c-border);
  background: #fff;
}
.ai-input :deep(.el-textarea__inner) {
  font-size: 13px;
}
.ai-send {
  align-self: flex-end;
  height: 40px;
}
</style>

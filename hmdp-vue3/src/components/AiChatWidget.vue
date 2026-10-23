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
  <div v-if="!visible" class="ai-fab" @click="visible = true">
    <el-icon :size="22"><ChatDotRound /></el-icon>
    <span>智能客服</span>
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
  right: 24px;
  bottom: 92px;
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  border-radius: 24px;
  background: linear-gradient(135deg, #ff9a3c, #ff6633);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(255, 102, 51, 0.35);
  transition: transform 0.2s, box-shadow 0.2s;
}
.ai-fab:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 22px rgba(255, 102, 51, 0.45);
}

.ai-panel {
  position: fixed;
  right: 24px;
  bottom: 92px;
  z-index: 2000;
  width: 380px;
  height: 540px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
  overflow: hidden;
}

.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: linear-gradient(135deg, #ff9a3c, #ff6633);
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
  background: #f7f8fa;
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
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-msg.assistant .ai-bubble {
  background: #fff;
  color: #333;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.ai-msg.user .ai-bubble {
  background: #ff6633;
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
  background: #bbb;
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
  border-top: 1px solid #f0f0f0;
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

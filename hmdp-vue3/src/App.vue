<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AiChatWidget from '@/components/AiChatWidget.vue'

const route = useRoute()

// 这些页面不显示客服入口：登录/注册（未登录）与发布笔记（避免遮挡编辑区）
const HIDDEN_PATHS = ['/login', '/register', '/blogEdit']
const showChat = computed(() => !HIDDEN_PATHS.includes(route.path))
</script>

<template>
  <div>
    <router-view></router-view>
    <AiChatWidget v-if="showChat" />
  </div>
</template>

<style scoped></style>

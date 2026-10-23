<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AiChatWidget from '@/components/AiChatWidget.vue'

const route = useRoute()
// 登录、注册页不显示客服入口
const showChat = computed(() => !['LoginPage', 'RegisterPage'].includes(route.name))
</script>

<template>
  <div>
    <router-view></router-view>
    <AiChatWidget v-if="showChat" />
  </div>
</template>

<style scoped></style>

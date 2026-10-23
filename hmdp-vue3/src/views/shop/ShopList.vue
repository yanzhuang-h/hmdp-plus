<template>
  <div class="shop-list-container">
    <!-- 头部 -->
    <div class="header">
      <div class="header-back-btn" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </div>
      <div class="header-title">{{ typeName }}</div>
      <div class="header-search">
        <el-icon><Search /></el-icon>
      </div>
    </div>

    <!-- 排序栏 -->
    <div class="sort-bar">
      <div class="sort-item">
        <el-dropdown trigger="click" @command="handleCommand">
          <span class="el-dropdown-link">
            {{ typeName
            }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="t in types" :key="t.id" :command="t">
                {{ t.name }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <div class="sort-item" @click="sortAndQuery('')">
        距离 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
      </div>
      <div class="sort-item" @click="sortAndQuery('comments')">
        人气 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
      </div>
      <div class="sort-item" @click="sortAndQuery('score')">
        评分 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
      </div>
    </div>

    <!-- 店铺列表 -->
    <div class="shop-list" @scroll="onScroll">
      <div
        class="shop-box"
        v-for="s in shops"
        :key="s.id"
        @click="toDetail(s.id)"
      >
        <div class="shop-img"><img :src="s.images" alt="" /></div>
        <div class="shop-info">
          <div class="shop-title shop-item">{{ s.name }}</div>
          <div class="shop-rate shop-item">
            <el-rate
              v-model="s.score"
              disabled
              :max="5"
              :value="s.score / 10"
              text-color="#F63"
              show-score
            />
            <span>{{ s.comments }}条</span>
          </div>
          <div class="shop-area shop-item">
            <span>{{ s.area }}</span>
            <span v-if="s.distance">
              {{
                s.distance < 1000
                  ? s.distance.toFixed(1) + 'm'
                  : (s.distance / 1000).toFixed(1) + 'km'
              }}
            </span>
          </div>
          <div class="shop-avg shop-item">￥{{ s.avgPrice }}/人</div>
          <div class="shop-address shop-item">
            <el-icon><Location /></el-icon>
            <span>{{ s.address }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ArrowLeft, ArrowDown, Search, Location } from '@element-plus/icons-vue'
import { getShopList, getShopTypeList } from '@/api/shop'
import { throttle } from '@/utils/scroll'
import { firstImage } from '@/utils/image'

const router = useRouter()
const route = useRoute()

// 响应式数据
const types = ref([])
const shops = ref([])
const typeName = ref('')
const isReachBottom = ref(false)
const params = ref({
  typeId: 0,
  current: 1,
  sortBy: '',
  x: 120.149993,
  y: 30.334229
})

// 获取店铺类型列表
const queryTypes = async () => {
  try {
    const { data } = await getShopTypeList()
    types.value = data
    console.log('getShopTypeList', types.value)
  } catch (error) {
    console.error(error)
    ElMessage.error('获取店铺类型失败')
  }
}

// 获取店铺列表
const queryShops = async () => {
  try {
    const { data } = await getShopList(params.value)
    if (!data) return
    data.forEach((s) => {
      if (!s) return
      // 商铺图片多为外部绝对地址，firstImage 会原样返回并处理空值
      s.images = firstImage(s.images, '')
    })
    shops.value = shops.value.concat(data)
  } catch (error) {
    console.error(error)
    ElMessage.error('获取店铺列表失败')
  }
}

// 处理类型选择
const handleCommand = (type) => {
  console.log('handleCommand', type)
  router.push({
    path: '/shoplist',
    query: {
      type: type.id,
      name: type.name
    }
  })
}

// 排序和查询
const sortAndQuery = (sortBy) => {
  params.value.sortBy = sortBy
  queryShops()
}

// 返回上一页
const goBack = () => {
  router.back()
}

// 跳转到店铺详情
const toDetail = (id) => {
  router.push(`/shopDetail/${id}`)
}

// 滚动加载
const onScroll = throttle((e) => {
  const { scrollTop, offsetHeight, scrollHeight } = e.target
  if (scrollTop + offsetHeight + 1 > scrollHeight && !isReachBottom.value) {
    isReachBottom.value = true
    params.value.current++
    queryShops()
  } else {
    isReachBottom.value = false
  }
}, 200)

// 初始化
const initData = () => {
  params.value.typeId = Number(route.query.type) || 0
  typeName.value = route.query.name || ''
  shops.value = [] // 清空店铺列表
  params.value.current = 1 // 重置页码
  queryTypes()
  queryShops()
}

// 监听路由参数变化
watch(
  () => route.query,
  () => {
    initData()
  }
)
onMounted(() => {
  initData()
})
</script>

<style scoped>
.shop-list-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--c-bg);
}

.header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background-color: var(--c-card);
  position: sticky;
  top: 0;
  z-index: 100;
  border-bottom: 1px solid var(--c-border);
}

.header-back-btn {
  padding: 4px;
  cursor: pointer;
  color: var(--c-text);
  display: flex;
  align-items: center;
}

.header-title {
  flex: 1;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  letter-spacing: 0.2px;
}

.header-search {
  padding: 4px;
  cursor: pointer;
  color: var(--c-text-sub);
  display: flex;
  align-items: center;
}

.sort-bar {
  display: flex;
  background-color: var(--c-card);
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-border);
  gap: 6px;
}

.sort-item {
  flex: 1;
  text-align: center;
  cursor: pointer;
  font-size: 12px;
  color: var(--c-text-sub);
  padding: 6px 0;
  border-radius: var(--radius-xs);
  transition: color 0.18s ease, background 0.18s ease;
}
.sort-item:active {
  color: var(--c-primary);
  background: var(--c-primary-soft);
}

.shop-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.shop-box {
  display: flex;
  padding: 12px;
  margin-bottom: 8px;
  background-color: var(--c-card);
  border-radius: var(--radius-lg);
  border: 1px solid var(--c-border);
  cursor: pointer;
  transition: border-color 0.18s ease, transform 0.18s ease;
}
.shop-box:active {
  transform: scale(0.988);
  border-color: #ddd9d2;
}

.shop-img {
  width: 78px;
  height: 78px;
  margin-right: 12px;
  flex-shrink: 0;
}

.shop-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: var(--radius-sm);
  display: block;
}

.shop-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  min-width: 0;
}

.shop-item {
  margin-bottom: 0;
}

.shop-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  letter-spacing: 0.1px;
}

.shop-rate {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--c-text-sub);
}

.shop-area {
  color: var(--c-text-muted);
  font-size: 12px;
}

.shop-avg {
  color: var(--c-price);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.shop-address {
  color: var(--c-text-muted);
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
}
.shop-address span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
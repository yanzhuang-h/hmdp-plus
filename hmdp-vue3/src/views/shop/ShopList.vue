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
  background: #f5f6f8;
}

.header {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  background-color: #fff;
  position: sticky;
  top: 0;
  z-index: 100;
  box-shadow: 0 1px 0 rgba(17, 24, 39, 0.06);
}

.header-back-btn {
  padding: 5px;
  cursor: pointer;
  color: #1f2329;
  display: flex;
  align-items: center;
}

.header-title {
  flex: 1;
  text-align: center;
  font-size: 17px;
  font-weight: 600;
  color: #1f2329;
}

.header-search {
  padding: 5px;
  cursor: pointer;
  color: #6b7280;
  display: flex;
  align-items: center;
}

.sort-bar {
  display: flex;
  background-color: #fff;
  padding: 10px 12px;
  border-bottom: 1px solid rgba(17, 24, 39, 0.06);
  gap: 8px;
}

.sort-item {
  flex: 1;
  text-align: center;
  cursor: pointer;
  font-size: 13px;
  color: #6b7280;
  transition: color 0.18s ease;
}
.sort-item:active {
  color: #ff6633;
}

.shop-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.shop-box {
  display: flex;
  padding: 10px;
  margin-bottom: 8px;
  background-color: #fff;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 0 2px 10px rgba(17, 24, 39, 0.06);
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.shop-box:active {
  transform: scale(0.988);
  box-shadow: 0 1px 6px rgba(17, 24, 39, 0.1);
}

.shop-img {
  width: 80px;
  height: 80px;
  margin-right: 10px;
  flex-shrink: 0;
}

.shop-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 10px;
  display: block;
}

.shop-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  min-width: 0;
}

.shop-item {
  margin-bottom: 0;
}

.shop-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2329;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-rate {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #6b7280;
}

.shop-area {
  color: #6b7280;
  font-size: 12px;
}

.shop-avg {
  color: #ff6633;
  font-size: 12px;
  font-weight: 600;
}

.shop-address {
  color: #6b7280;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
}
.shop-address span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
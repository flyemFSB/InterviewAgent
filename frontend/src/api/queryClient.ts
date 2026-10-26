import {QueryClient} from '@tanstack/react-query'

/**
 * 全局 QueryClient。
 * - staleTime 30s：列表页来回切换不重复打后端
 * - retry 1：失败重试一次，再交给页面的错误分支
 * - refetchOnWindowFocus false：轮询类页面自己用 refetchInterval 控制刷新节奏
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})

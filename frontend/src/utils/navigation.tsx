import type {ReactNode} from 'react'
import {useCallback} from 'react'
import {
  Link as TanStackLink,
  Navigate as TanStackNavigate,
  Outlet as TanStackOutlet,
  useLocation,
  useNavigate as useTanStackNavigate,
  useParams as useTanStackParams,
} from '@tanstack/react-router'

export {useLocation}
export const Outlet = TanStackOutlet

/**
 * TanStack Router 的 navigate/Link 只接受类型化的 {to, params, search}。
 * 项目里原本有大量 navigate('/a/b')、navigate(`/a/${id}`) 的写法，这里统一收口做薄适配：
 * - 字符串按 '?' 拆成 path + search，保留 react-router 时代的调用风格
 * - 需要类型安全的场景仍可直接传 {to: '/interview/create/$requestId', params}
 * 只有这一个文件对 TanStack 做断言，业务代码不用到处 as never。
 */
export type NavTarget =
  | string
  | {to: string; params?: Record<string, string | number>; search?: Record<string, unknown>}

export type NavOptions = {replace?: boolean; state?: unknown}

function toOptions(target: NavTarget, options?: NavOptions) {
  // TanStack Router 的 navigate 支持 href：直接给具体 URL，由 router 自己匹配路由树。
  // 这样 '/history/12'、'/interview/create/<uuid>' 这类动态路径都不用在调用点改写成 params 形式。
  if (typeof target === 'string') {
    return {href: target, ...options} as never
  }
  return {...target, ...options} as never
}

export function useNavigate() {
  const navigate = useTanStackNavigate()
  // 保持引用稳定，避免调用方把 navigate 放进 useEffect 依赖后重复触发
  return useCallback(
    (target: NavTarget, options?: NavOptions) => navigate(toOptions(target, options)),
    [navigate],
  )
}

export function useParams<T extends Record<string, string | undefined> = Record<string, string | undefined>>() {
  // strict: false 让同一包装器可服务多个路由（如 /interviews/$sessionId 与 /knowledgebase-interview/$kbId/interviews/$sessionId）
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return useTanStackParams({strict: false} as any) as T
}

export function Link({to, children, ...rest}: {to: string; children?: ReactNode} & Record<string, unknown>) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return <TanStackLink {...(rest as any)} href={to as any}>{children}</TanStackLink>
}

export function Navigate({to, ...rest}: {to: string} & Record<string, unknown>) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return <TanStackNavigate {...(rest as any)} href={to as any} />
}

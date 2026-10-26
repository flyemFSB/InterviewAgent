import {createContext, useContext} from 'react'

export interface InterviewModalContextValue {
  openInterviewModalWithResume: (resumeId: number) => void
}

/**
 * 代替 react-router 的 <Outlet context> / useOutletContext：
 * TanStack Router 的 Outlet 不传 context，这里用普通 React context 中转。
 */
export const InterviewModalContext = createContext<InterviewModalContextValue | null>(null)

export function useInterviewModal() {
  const ctx = useContext(InterviewModalContext)
  if (!ctx) {
    throw new Error('useInterviewModal must be used inside <Layout>')
  }
  return ctx
}

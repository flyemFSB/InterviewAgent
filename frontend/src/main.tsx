import React from 'react'
import ReactDOM from 'react-dom/client'
import {QueryClientProvider} from '@tanstack/react-query'
import App from './App'
import {queryClient} from './api/queryClient'
import './index.css'

// 初始化深色模式（避免页面闪烁）
(function initTheme() {
    const stored = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const isDark = stored === 'dark' || (!stored && prefersDark);
    if (isDark) {
        document.documentElement.classList.add('dark');
    }
})();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
)

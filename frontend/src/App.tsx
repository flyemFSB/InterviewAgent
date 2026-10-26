import {createRootRoute, createRoute, createRouter, RouterProvider} from '@tanstack/react-router';
import Layout from './components/Layout';
import {useInterviewModal} from './components/interviewModalContext';
import {useEffect, useState, Suspense, lazy} from 'react';
import {historyApi, type InterviewDetail} from './api/history';
import type {Difficulty} from './components/UnifiedInterviewModal';
import type {CategoryDTO} from './api/skill';
import {Loader2} from 'lucide-react';
import {ROUTES} from './constants/routes';
import {Navigate, useLocation, useNavigate, useParams} from './utils/navigation';

// Lazy load components
const UploadPage = lazy(() => import('./pages/UploadPage'));
const HistoryList = lazy(() => import('./pages/HistoryPage'));
const ResumeDetailPage = lazy(() => import('./pages/ResumeDetailPage'));
const Interview = lazy(() => import('./pages/InterviewPage'));
const InterviewHistoryPage = lazy(() => import('./pages/InterviewHistoryPage'));
const KnowledgeBaseQueryPage = lazy(() => import('./pages/KnowledgeBaseQueryPage'));
const KnowledgeBaseUploadPage = lazy(() => import('./pages/KnowledgeBaseUploadPage'));
const KnowledgeBaseManagePage = lazy(() => import('./pages/KnowledgeBaseManagePage'));
const KnowledgeBaseInterviewPage = lazy(() => import('./pages/KnowledgeBaseInterviewLandingPage'));
const KnowledgeBaseInterviewQuestionsPage = lazy(() => import('./pages/KnowledgeBaseInterviewQuestionsPage'));
const KnowledgeBaseInterviewSessionPage = lazy(() => import('./pages/KnowledgeBaseInterviewSessionPage'));
const VoiceInterviewPage = lazy(() => import('./pages/VoiceInterviewPage'));
const VoiceInterviewEvaluationPage = lazy(() => import('./pages/VoiceInterviewEvaluationPage'));
const InterviewSchedulePage = lazy(() => import('./pages/InterviewSchedulePage'));
const InterviewHubPage = lazy(() => import('./pages/InterviewHubPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const InterviewDetailPanel = lazy(() => import('./components/InterviewDetailPanel'));

// Loading component
const Loading = () => (
  <div className="flex items-center justify-center min-h-[50vh]">
    <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
  </div>
);

// 上传页面包装器
function UploadPageWrapper() {
  const navigate = useNavigate();

  return <UploadPage onBack={() => navigate(ROUTES.resumeHistory)} />;
}

// 历史记录列表包装器
function HistoryListWrapper() {
  const navigate = useNavigate();

  const handleSelectResume = (id: number) => {
    navigate(`/history/${id}`);
  };

  return <HistoryList onSelectResume={handleSelectResume} />;
}

// 简历详情包装器
function ResumeDetailWrapper() {
  const {resumeId} = useParams<{ resumeId: string }>();
  const navigate = useNavigate();
  const {openInterviewModalWithResume} = useInterviewModal();

  if (!resumeId) {
    return <Navigate to="/history" replace/>;
  }

  const handleBack = () => {
    navigate('/history');
  };

  const handleStartInterview = (id: number) => {
    openInterviewModalWithResume(id);
  };

  return (
    <ResumeDetailPage
      resumeId={parseInt(resumeId, 10)}
      onBack={handleBack}
      onStartInterview={handleStartInterview}
    />
  );
}

interface InterviewEntryState {
  resumeId?: number;
  resumeText?: string;
  sessionIdToResume?: string;
  interviewConfig?: {
    skillId?: string;
    difficulty?: Difficulty;
    questionCount?: number;
    llmProvider?: string;
    customCategories?: CategoryDTO[];
    jdText?: string;
  };
}

// 模拟面试包装器
function InterviewWrapper() {
  const {resumeId, requestId, activeSessionId} = useParams<{
    resumeId: string;
    requestId: string;
    activeSessionId: string;
  }>();
  const navigate = useNavigate();
  const location = useLocation();
  const entryState = (location.state as InterviewEntryState | undefined) ?? {};
  const [resumeText, setResumeText] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const effectiveResumeId = resumeId ? parseInt(resumeId, 10) : entryState.resumeId;

  useEffect(() => {
    // 优先从location state获取resumeText
    const stateText = entryState.resumeText;
    if (stateText) {
      setResumeText(stateText);
      setLoading(false);
    } else if (effectiveResumeId) {
      // 如果没有，从API获取简历详情
      historyApi.getResumeDetail(effectiveResumeId)
        .then(resume => {
          setResumeText(resume.resumeText);
          setLoading(false);
        })
        .catch(err => {
          console.error('获取简历文本失败', err);
          setLoading(false);
        });
    } else {
      setLoading(false);
    }
  }, [effectiveResumeId, entryState.resumeText]);

  const handleBack = () => {
    if (effectiveResumeId) {
      navigate(`/history/${effectiveResumeId}`, {replace: false});
      return;
    }
    navigate('/history', {replace: false});
  };

  const handleInterviewComplete = () => {
    // 面试完成后跳转到面试记录页
    navigate('/interviews');
  };

  const handleSessionCreated = (sessionId: string) => {
    navigate(ROUTES.interviewSession(sessionId), {replace: true, state: entryState});
  };

  if (!requestId && !activeSessionId && !entryState.sessionIdToResume) {
    return (
      <Navigate
        to={ROUTES.interviewCreate(crypto.randomUUID())}
        replace
        state={{...entryState, resumeId: effectiveResumeId}}
      />
    );
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center">
          <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full mx-auto mb-4 animate-spin" />
          <p className="text-slate-500">加载中...</p>
        </div>
      </div>
    );
  }

  return (
    <Interview
      resumeText={resumeText}
      resumeId={effectiveResumeId}
      sessionIdToResume={activeSessionId ?? entryState.sessionIdToResume}
      requestId={requestId}
      initialConfig={entryState.interviewConfig}
      onBack={handleBack}
      onSessionCreated={handleSessionCreated}
      onInterviewComplete={handleInterviewComplete}
    />
  );
}

// 面试记录页面包装器
function InterviewHistoryWrapper() {
  const navigate = useNavigate();
  const {openInterviewModalWithResume} = useInterviewModal();

  const handleBack = () => {
    navigate('/history');
  };

  const handleViewInterview = async (sessionId: string, _resumeId?: number) => {
    navigate(`/interviews/${sessionId}`);
  };

  const handleRestartInterview = (resumeId: number) => {
    openInterviewModalWithResume(resumeId);
  };

  const handleContinueInterview = (sessionId: string) => {
    navigate(ROUTES.interviewSession(sessionId));
  };

  return <InterviewHistoryPage onBack={handleBack} onViewInterview={handleViewInterview}
                               onRestartInterview={handleRestartInterview}
                               onContinueInterview={handleContinueInterview}/>;
}

// 知识库面试记录页面包装器
function KnowledgeBaseInterviewHistoryWrapper() {
  const navigate = useNavigate();
  const {knowledgeBaseId} = useParams<{ knowledgeBaseId: string }>();
  const id = knowledgeBaseId ? Number(knowledgeBaseId) : NaN;

  if (Number.isNaN(id)) {
    return <Navigate to="/knowledgebase-interview" replace/>;
  }

  const handleBack = () => {
    navigate(`/knowledgebase-interview/${id}/questions`);
  };

  const handleViewInterview = (sessionId: string) => {
    navigate(`/knowledgebase-interview/${id}/interviews/${sessionId}`);
  };

  const handleContinueInterview = (sessionId: string) => {
    navigate(`/knowledgebase-interview/${sessionId}`, {state: {knowledgeBaseId: id}});
  };

  return (
    <InterviewHistoryPage
      knowledgeBaseId={id}
      onBack={handleBack}
      onViewInterview={handleViewInterview}
      onContinueInterview={handleContinueInterview}
    />
  );
}

// 面试详情报告页面包装器
function InterviewDetailPageWrapper() {
  const {sessionId, knowledgeBaseId} = useParams<{ sessionId: string; knowledgeBaseId?: string }>();
  const navigate = useNavigate();
  const [interview, setInterview] = useState<InterviewDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const backTarget = knowledgeBaseId
    ? `/knowledgebase-interview/${knowledgeBaseId}/interviews`
    : '/interviews';

  useEffect(() => {
    if (!sessionId) {
      navigate(backTarget);
      return;
    }
    historyApi.getInterviewDetail(sessionId)
      .then(detail => {
        setInterview(detail);
        setLoading(false);
      })
      .catch(() => {
        setError('加载面试详情失败');
        setLoading(false);
      });
  }, [sessionId, navigate, backTarget]);

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
      </div>
    );
  }

  if (error || !interview) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="text-center">
          <p className="text-red-500 mb-4">{error || '面试记录不存在'}</p>
          <button
            onClick={() => navigate(backTarget)}
            className="px-5 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600"
          >
            返回面试记录
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="flex items-center gap-3 mb-6">
        <button
          onClick={() => navigate(backTarget)}
          className="p-2 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-lg transition-colors"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </button>
        <h1 className="text-xl font-bold text-slate-900 dark:text-white">
          面试详情 #{sessionId!.slice(-8)}
        </h1>
      </div>
      <InterviewDetailPanel interview={interview} />
    </div>
  );
}

function KnowledgeBaseManagePageWrapper() {
  const navigate = useNavigate();

  const handleUpload = () => {
    navigate(ROUTES.knowledgebaseUpload);
  };

  const handleChat = () => {
    navigate('/knowledgebase/chat');
  };

  return <KnowledgeBaseManagePage onUpload={handleUpload} onChat={handleChat} />;
}

// 知识库问答页面包装器
function KnowledgeBaseQueryPageWrapper() {
  const navigate = useNavigate();
  const location = useLocation();
  const isChatMode = location.pathname === '/knowledgebase/chat';

  const handleBack = () => {
    if (isChatMode) {
      navigate('/knowledgebase');
    } else {
      navigate('/history');
    }
  };

  const handleUpload = () => {
    navigate(ROUTES.knowledgebaseUpload);
  };

  return <KnowledgeBaseQueryPage onBack={handleBack} onUpload={handleUpload} />;
}

// 知识库上传页面包装器
function KnowledgeBaseUploadPageWrapper() {
  const navigate = useNavigate();

  const handleBack = () => {
    navigate('/knowledgebase');
  };

  return <KnowledgeBaseUploadPage onBack={handleBack} />;
}

// 语音面试页面包装器
function VoiceInterviewPageWrapper() {
  return <VoiceInterviewPage />;
}

// ---- 路由树（TanStack Router，code-based）----
const rootRoute = createRootRoute({
  component: () => (
    <Suspense fallback={<Loading />}>
      <Layout />
    </Suspense>
  ),
});

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: () => <Navigate to="/history" replace />,
});

const routeDefs = [
  {path: '/upload', component: UploadPageWrapper},
  {path: '/history', component: HistoryListWrapper},
  {path: '/history/$resumeId', component: ResumeDetailWrapper},
  {path: '/interview-hub', component: InterviewHubPage},
  {path: '/interviews', component: InterviewHistoryWrapper},
  {path: '/interviews/$sessionId', component: InterviewDetailPageWrapper},
  {path: '/interview', component: InterviewWrapper},
  {path: '/interview/create/$requestId', component: InterviewWrapper},
  {path: '/interview/session/$activeSessionId', component: InterviewWrapper},
  {path: '/interview/$resumeId', component: InterviewWrapper},
  {path: '/voice-interview', component: VoiceInterviewPageWrapper},
  {path: '/voice-interview/$sessionId/evaluation', component: VoiceInterviewEvaluationPage},
  {path: '/knowledgebase', component: KnowledgeBaseManagePageWrapper},
  {path: '/knowledgebase-interview', component: KnowledgeBaseInterviewPage},
  {path: '/knowledgebase-interview/$knowledgeBaseId/questions', component: KnowledgeBaseInterviewQuestionsPage},
  {path: '/knowledgebase-interview/$knowledgeBaseId/interviews', component: KnowledgeBaseInterviewHistoryWrapper},
  {path: '/knowledgebase-interview/$knowledgeBaseId/interviews/$sessionId', component: InterviewDetailPageWrapper},
  {path: '/knowledgebase-interview/$sessionId', component: KnowledgeBaseInterviewSessionPage},
  {path: '/knowledgebase/upload', component: KnowledgeBaseUploadPageWrapper},
  {path: '/interview-schedule', component: InterviewSchedulePage},
  {path: '/settings', component: SettingsPage},
  {path: '/knowledgebase/chat', component: KnowledgeBaseQueryPageWrapper},
] as const;

const childRoutes = routeDefs.map((def) =>
  createRoute({
    getParentRoute: () => rootRoute,
    path: def.path,
    // 懒加载组件由 rootRoute 的 Suspense 兜住
    component: def.component as never,
  }),
);

const routeTree = rootRoute.addChildren([indexRoute, ...childRoutes]);

export const router = createRouter({routeTree});

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

function App() {
  return <RouterProvider router={router} />;
}

export default App;

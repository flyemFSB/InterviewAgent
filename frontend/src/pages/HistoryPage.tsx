import {useState} from 'react';
import {useQuery, useQueryClient} from '@tanstack/react-query';
import {useNavigate} from '../utils/navigation';
import {AnimatePresence, motion} from 'framer-motion';
import {AlertCircle, CheckCircle, Clock, FileStack, RefreshCw, Sparkles, Upload} from 'lucide-react';
import {historyApi, type ResumeListItem} from '../api/history';
import {createColumnHelper, flexRender, tableFeatures, useTable} from '@tanstack/react-table';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import {formatDateOnly} from '../utils/date';
import {getScoreProgressColor} from '../utils/score';
import { ROUTES } from '../constants/routes';

interface HistoryListProps {
  onSelectResume: (id: number) => void;
}

function isAnalyzing(status?: string): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

function AnalyzeStatusIcon({status}: { status?: string }) {
  if (status === 'FAILED') return <AlertCircle className="w-4 h-4 text-red-500 dark:text-red-400"/>;
  if (isAnalyzing(status)) return <RefreshCw className="w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin"/>;
  if (status === 'COMPLETED') return <CheckCircle className="w-4 h-4 text-green-500 dark:text-green-400"/>;
  return <Clock className="w-4 h-4 text-yellow-500 dark:text-yellow-400"/>;
}

function getAnalyzeStatusText(status?: string): string {
  if (status === 'FAILED') return '分析失败';
  if (status === 'PROCESSING') return '分析中';
  if (status === 'PENDING') return '等待分析';
  if (status === 'COMPLETED') return '分析完成';
  return '待分析';
}



const tableFeaturesConfig = tableFeatures({});
const columnHelper = createColumnHelper<typeof tableFeaturesConfig, ResumeListItem>();

const TD_CLASS: Record<string, string> = {
  filename: 'px-6 py-5',
  uploadedAt: 'px-6 py-5 text-slate-500 dark:text-slate-400',
  analyzeStatus: 'px-6 py-5',
  latestScore: 'px-6 py-5',
  interviewStatus: 'px-6 py-5',
  actions: 'px-4',
};
const TH_CLASS: Record<string, string> = {
  actions: 'w-20',
};

export default function HistoryList({onSelectResume}: HistoryListProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchTerm, setSearchTerm] = useState('');
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ id: number; filename: string } | null>(null);

  // TanStack Query：手动 setInterval 轮询 + resumesEqual 去抖都交给库。
  // refetchInterval 返回 false 时不轮询；数据未变化时 structural sharing 不会触发重渲染。
  const {data: resumes = [], isLoading: loading} = useQuery({
    queryKey: ['resumes'],
    queryFn: () => historyApi.getResumes(),
    refetchInterval: (query) =>
      (query.state.data ?? []).some(r => isAnalyzing(r.analyzeStatus)) ? 3000 : false,
  });

  const handleDeleteClick = (id: number, filename: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteConfirm({id, filename});
  };

  const handleDeleteConfirm = async () => {
    if (!deleteConfirm) return;

    const {id} = deleteConfirm;
    setDeletingId(id);
    try {
      await historyApi.deleteResume(id);
      await queryClient.refetchQueries({queryKey: ['resumes']});
      setDeleteConfirm(null);
    } catch (err) {
      alert(err instanceof Error ? err.message : '删除失败，请稍后重试');
    } finally {
      setDeletingId(null);
    }
  };

  const filteredResumes = resumes.filter(resume =>
    resume.filename.toLowerCase().includes(searchTerm.toLowerCase())
  );

  // TanStack Table v9：tableFeatures + useTable，列用 display 列承载原有富内容单元格
  const columns = [
    columnHelper.display({
      id: 'filename',
      header: '简历名称',
      cell: ({row}) => (
        <div className="flex items-center gap-4">
          <div
            className="w-10 h-10 bg-primary-50 dark:bg-primary-900/30 rounded-xl flex items-center justify-center text-primary-500 dark:text-primary-400">
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
              <path d="M14 2H6C5.46957 2 4.96086 2.21071 4.58579 2.58579C4.21071 2.96086 4 3.46957 4 4V20C4 20.5304 4.21071 21.0391 4.58579 21.4142C4.96086 21.7893 5.46957 22 6 22H18C18.5304 22 19.0391 21.7893 19.4142 21.4142C19.7893 21.0391 20 20.5304 20 20V8L14 2Z"
                    stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              <polyline points="14,2 14,8 20,8" stroke="currentColor" strokeWidth="2"
                        strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </div>
          <span className="font-medium text-slate-800 dark:text-white">{row.original.filename}</span>
        </div>
      ),
    }),
    columnHelper.display({
      id: 'uploadedAt',
      header: '上传日期',
      cell: ({row}) => formatDateOnly(row.original.uploadedAt),
    }),
    columnHelper.display({
      id: 'analyzeStatus',
      header: '分析状态',
      cell: ({row}) => (
        <div className="flex items-center gap-2">
          <AnalyzeStatusIcon status={row.original.analyzeStatus}/>
          <span className="text-sm text-slate-600 dark:text-slate-300">
            {getAnalyzeStatusText(row.original.analyzeStatus)}
          </span>
        </div>
      ),
    }),
    columnHelper.display({
      id: 'latestScore',
      header: 'AI 评分',
      cell: ({row}) => {
        const resume = row.original;
        if (resume.analyzeStatus === 'COMPLETED' && resume.latestScore !== undefined) {
          return (
            <div className="flex items-center gap-3">
              <div className="w-20 h-2 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                <div
                  className={`h-full ${getScoreProgressColor(resume.latestScore)} rounded-full`}
                  style={{width: `${resume.latestScore}%`}}
                />
              </div>
              <span className="font-bold text-slate-800 dark:text-white">{resume.latestScore}</span>
            </div>
          );
        }
        if (isAnalyzing(resume.analyzeStatus)) {
          return <span className="text-blue-500 dark:text-blue-400 text-sm">生成中...</span>;
        }
        if (resume.analyzeStatus === 'FAILED') {
          return <span className="text-red-500 dark:text-red-400 text-sm" title={resume.analyzeError}>失败</span>;
        }
        return <span className="text-slate-400 dark:text-slate-500">-</span>;
      },
    }),
    columnHelper.display({
      id: 'interviewStatus',
      header: '面试状态',
      cell: ({row}) => row.original.interviewCount > 0 ? (
        <span
          className="inline-flex items-center gap-1.5 px-3 py-1 bg-emerald-50 dark:bg-emerald-900 text-emerald-600 rounded-full text-sm font-medium">
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2"/>
            <polyline points="9,12 11,14 15,10" stroke="currentColor" strokeWidth="2"
                      strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
          已完成
        </span>
      ) : (
        <span
          className="inline-flex px-3 py-1 bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300 rounded-full text-sm">待面试</span>
      ),
    }),
    columnHelper.display({
      id: 'actions',
      header: '',
      cell: ({row}) => (
        <div className="flex items-center gap-2">
          <button
            onClick={(e) => handleDeleteClick(row.original.id, row.original.filename, e)}
            disabled={deletingId === row.original.id}
            className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            title="删除简历"
          >
            {deletingId === row.original.id ? (
              <div className="w-5 h-5 border-2 border-red-500 border-t-transparent rounded-full animate-spin"/>
            ) : (
              <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
                <path d="M3 6H5H21M8 6V4C8 3.46957 8.21071 2.96086 8.58579 2.58579C8.96086 2.21071 9.46957 2 10 2H14C14.5304 2 15.0391 2.21071 15.4142 2.58579C15.7893 2.96086 16 3.46957 16 4V6M19 6V20C19 20.5304 18.7893 21.0391 18.4142 21.4142C18.0391 21.7893 17.5304 22 17 22H7C6.46957 22 5.96086 21.7893 5.58579 21.4142C5.21071 21.0391 5 20.5304 5 20V6H19Z"
                      stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                <path d="M10 11V17M14 11V17" stroke="currentColor" strokeWidth="2"
                      strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            )}
          </button>
          <svg
            className="w-5 h-5 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-1 transition-all"
            viewBox="0 0 24 24" fill="none">
            <polyline points="9,18 15,12 9,6" stroke="currentColor" strokeWidth="2"
                      strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        </div>
      ),
    }),
  ];

  const table = useTable({features: tableFeaturesConfig, columns, data: filteredResumes});

  return (
    <motion.div
      className="w-full"
      initial={{opacity: 0}}
      animate={{opacity: 1}}
    >
      {/* 头部 */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
            <FileStack className="w-7 h-7 text-primary-500" />
            简历管理
          </h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1">管理您的简历，AI 智能分析与评分</p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={() => navigate(ROUTES.resumeUpload)}
            className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 transition-colors"
          >
            <Upload className="w-4 h-4" />
            上传简历
          </button>
          <button
            onClick={() => navigate('/interview-hub')}
            className="flex items-center gap-2 px-4 py-2 bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
          >
            <Sparkles className="w-4 h-4" />
            模拟面试
          </button>
        </div>
      </div>

      {/* 搜索栏 */}
      <div className="mb-6">
        <div className="flex items-center gap-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-3 max-w-md focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 transition-all">
          <svg className="w-5 h-5 text-slate-400" viewBox="0 0 24 24" fill="none">
            <circle cx="11" cy="11" r="8" stroke="currentColor" strokeWidth="2"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
          </svg>
          <input
            type="text"
            placeholder="搜索简历..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="flex-1 outline-none text-slate-700 dark:text-slate-200 placeholder:text-slate-400 bg-transparent"
          />
        </div>
      </div>

      {/* 加载状态 */}
      {loading && (
        <div className="text-center py-20">
          <motion.div
            className="w-10 h-10 border-3 border-slate-200 dark:text-slate-200 border-t-primary-500 rounded-full mx-auto mb-4"
            animate={{rotate: 360}}
            transition={{duration: 1, repeat: Infinity, ease: "linear"}}
          />
          <p className="text-slate-500 dark:text-slate-400">加载中...</p>
        </div>
      )}

      {/* 空状态 */}
      {!loading && filteredResumes.length === 0 && (
        <motion.div
          className="text-center py-20 bg-white dark:bg-slate-800 rounded-2xl"
          initial={{opacity: 0, scale: 0.95}}
          animate={{opacity: 1, scale: 1}}
        >
          <div className="text-6xl mb-6">📄</div>
          <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">暂无简历记录</h3>
          <p className="text-slate-500 dark:text-slate-400">上传简历开始您的第一次 AI 面试分析</p>
        </motion.div>
      )}

      {/* 表格 */}
      {!loading && filteredResumes.length > 0 && (
        <motion.div
          className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm overflow-hidden"
          initial={{opacity: 0, y: 20}}
          animate={{opacity: 1, y: 0}}
          transition={{delay: 0.2}}
        >
          <table className="w-full">
            <thead>
            <tr className="bg-slate-50 dark:bg-slate-700/50 border-b border-slate-100 dark:border-slate-600">
              {table.getHeaderGroups().map(headerGroup => (
                headerGroup.headers.map(header => (
                  <th
                    key={header.id}
                    className={TH_CLASS[header.column.id] ?? 'text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide'}
                  >
                    {flexRender(header.column.columnDef.header, header.getContext())}
                  </th>
                ))
              ))}
            </tr>
            </thead>
            <tbody>
            <AnimatePresence>
              {table.getRowModel().rows.map((row, index) => (
                <motion.tr
                  key={row.id}
                  initial={{opacity: 0, x: -20}}
                  animate={{opacity: 1, x: 0}}
                  transition={{delay: index * 0.05}}
                  onClick={() => onSelectResume(row.original.id)}
                  className="border-b border-slate-100 dark:border-slate-700 last:border-0 hover:bg-slate-50 dark:hover:bg-slate-700 cursor-pointer transition-colors group"
                >
                  {row.getAllCells().map(cell => (
                    <td key={cell.id} className={TD_CLASS[cell.column.id] ?? 'px-6 py-5'}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  ))}
                </motion.tr>
              ))}
            </AnimatePresence>
            </tbody>
          </table>
        </motion.div>
      )}

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteConfirm !== null}
        item={deleteConfirm}
        itemType="简历"
        loading={deletingId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteConfirm(null)}
        customMessage={
          deleteConfirm ? (
            <>
              <p className="mb-2">确定要删除简历 <strong>"{deleteConfirm.filename}"</strong> 吗？</p>
              <p className="text-sm text-slate-500 dark:text-slate-400 mb-2">删除后将同时删除：</p>
              <ul className="text-sm text-slate-500 dark:text-red-400 list-disc list-inside mb-2">
                <li>简历评价记录</li>
                <li>所有模拟面试记录</li>
              </ul>
              <p className="text-sm font-semibold text-red-600">此操作不可恢复！</p>
            </>
          ) : undefined
        }
      />
    </motion.div>
  );
}

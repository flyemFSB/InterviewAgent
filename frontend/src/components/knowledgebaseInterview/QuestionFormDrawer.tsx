import {Dialog} from '@base-ui/react/dialog';
import {AppSelect} from '../ui/AppSelect';
import {useForm} from '@tanstack/react-form';
import {FileText, Loader2, X} from 'lucide-react';
import type {
  KnowledgeBaseQuestion,
  KnowledgeBaseQuestionFollowUp,
  SaveKnowledgeBaseQuestionRequest,
  KnowledgeBaseQuestionStatus,
} from '../../api/knowledgebase';
import {
  INPUT_CLASS,
  STATUS_OPTIONS,
  DIFFICULTY_OPTIONS,
} from '../../constants/knowledgebaseInterview';

export interface QuestionFormState {
  difficulty: string;
  type: string;
  category: string;
  question: string;
  topicSummary: string;
  referenceAnswer: string;
  keyPoints: string;
  scoringRubric: string;
  followUps: string;
  sourceContext: string;
  status: KnowledgeBaseQuestionStatus;
}

export function emptyQuestionForm(
  defaults?: Partial<Pick<QuestionFormState, 'difficulty' | 'category'>>
): QuestionFormState {
  return {
    difficulty: defaults?.difficulty || 'mid',
    type: 'KNOWLEDGE_BASE',
    category: defaults?.category || '',
    question: '',
    topicSummary: '',
    referenceAnswer: '',
    keyPoints: '',
    scoringRubric: '',
    followUps: '',
    sourceContext: '',
    status: 'DRAFT',
  };
}

function linesToArray(value: string): string[] {
  return value.split('\n').map(item => item.trim()).filter(Boolean);
}

function arrayToLines(values?: string[]): string {
  return (values ?? []).join('\n');
}

function followUpsToText(followUps: KnowledgeBaseQuestionFollowUp[]): string {
  return followUps
    .map(item => [
      item.question,
      item.referenceAnswer || '',
      (item.keyPoints || []).join(','),
      item.scoringRubric || '',
    ].join(' | '))
    .join('\n');
}

function textToFollowUps(value: string): KnowledgeBaseQuestionFollowUp[] {
  return linesToArray(value).map(line => {
    const [question, referenceAnswer, keyPoints, scoringRubric] = line.split('|').map(item => item.trim());
    return {
      question,
      referenceAnswer: referenceAnswer || null,
      keyPoints: keyPoints ? keyPoints.split(',').map(item => item.trim()).filter(Boolean) : [],
      scoringRubric: scoringRubric || null,
    };
  });
}

export function formFromQuestion(question: KnowledgeBaseQuestion): QuestionFormState {
  return {
    difficulty: question.difficulty,
    type: question.type || '',
    category: question.category || '',
    question: question.question,
    topicSummary: question.topicSummary || '',
    referenceAnswer: question.referenceAnswer || '',
    keyPoints: arrayToLines(question.keyPoints),
    scoringRubric: question.scoringRubric || '',
    followUps: followUpsToText(question.followUps),
    sourceContext: question.sourceContext || '',
    status: question.status,
  };
}

export function buildQuestionPayload(form: QuestionFormState): SaveKnowledgeBaseQuestionRequest {
  return {
    difficulty: form.difficulty,
    type: form.type.trim() || null,
    category: form.category.trim(),
    question: form.question.trim(),
    topicSummary: form.topicSummary.trim() || null,
    referenceAnswer: form.referenceAnswer.trim() || null,
    keyPoints: linesToArray(form.keyPoints),
    scoringRubric: form.scoringRubric.trim() || null,
    followUps: textToFollowUps(form.followUps),
    sourceContext: form.sourceContext.trim() || null,
    status: form.status,
  };
}

interface QuestionFormDrawerProps {
  /** 表单初始值；组件内部由 TanStack Form 接管状态 */
  initialForm: QuestionFormState;
  editing: boolean;
  saving: boolean;
  error: string;
  categoryOptions?: string[];
  onClose: () => void;
  onSubmit: (payload: SaveKnowledgeBaseQuestionRequest) => void | Promise<void>;
}

export default function QuestionFormDrawer({
  initialForm,
  editing,
  saving,
  error,
  categoryOptions = [],
  onClose,
  onSubmit,
}: QuestionFormDrawerProps) {
  // TanStack Form：状态由组件自己持有，父组件只提供初始值与提交回执
  const form = useForm({
    defaultValues: initialForm,
    onSubmit: async ({value}) => {
      await onSubmit(buildQuestionPayload(value));
    },
  });

  return (
    <Dialog.Root open onOpenChange={(next) => { if (!next) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-50 bg-black/40" />
        <Dialog.Popup className="fixed right-0 top-0 z-50 h-full w-full max-w-2xl overflow-y-auto border-l border-slate-200 bg-white shadow-2xl outline-none dark:border-slate-700 dark:bg-slate-900">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              event.stopPropagation();
              void form.handleSubmit();
            }}
            className="h-full"
          >
            <div className="sticky top-0 z-10 flex items-center justify-between border-b border-slate-100 bg-white px-5 py-4 dark:border-slate-700 dark:bg-slate-900">
              <div>
                <Dialog.Title className="font-bold text-slate-900 dark:text-white">
                  {editing ? '编辑题目' : '新增题目'}
                </Dialog.Title>
                <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">维护题干、答案、评分规则和追问</p>
              </div>
              <button
                type="button"
                onClick={onClose}
                aria-label="关闭"
                className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <div className="space-y-4 p-5">
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                <Field label="难度">
                  <form.Field name="difficulty">
                    {(field) => (
                      <AppSelect
                        value={field.state.value}
                        options={DIFFICULTY_OPTIONS}
                        onChange={(value) => field.handleChange(value)}
                        className={INPUT_CLASS}
                        ariaLabel="难度"
                      />
                    )}
                  </form.Field>
                </Field>
                <Field label="状态">
                  <form.Field name="status">
                    {(field) => (
                      <AppSelect
                        value={field.state.value}
                        options={STATUS_OPTIONS.filter(option => option.value)}
                        onChange={(value) => field.handleChange(value as KnowledgeBaseQuestionStatus)}
                        className={INPUT_CLASS}
                        ariaLabel="状态"
                      />
                    )}
                  </form.Field>
                </Field>
              </div>

              <Field label="面试方向（用于筛选和开始面试）">
                <form.Field name="category">
                  {(field) => (
                    <>
                      <input
                        value={field.state.value}
                        list="knowledge-base-question-category-options"
                        onChange={(event) => field.handleChange(event.target.value)}
                        onBlur={field.handleBlur}
                        className={INPUT_CLASS}
                        placeholder="例如 整洁架构 / Redis / JVM 调优"
                      />
                      {categoryOptions.length > 0 && (
                        <datalist id="knowledge-base-question-category-options">
                          {categoryOptions.map(category => (
                            <option key={category} value={category} />
                          ))}
                        </datalist>
                      )}
                      <p className="mt-1 text-xs text-slate-400 dark:text-slate-500">
                        可输入新方向，或从已有方向中选择。
                      </p>
                    </>
                  )}
                </form.Field>
              </Field>

              <Field label="题干">
                <form.Field name="question">
                  {(field) => (
                    <textarea
                      value={field.state.value}
                      onChange={(event) => field.handleChange(event.target.value)}
                      onBlur={field.handleBlur}
                      className={`${INPUT_CLASS} min-h-24 resize-y`}
                    />
                  )}
                </form.Field>
              </Field>

              <Field label="参考答案">
                <form.Field name="referenceAnswer">
                  {(field) => (
                    <textarea
                      value={field.state.value}
                      onChange={(event) => field.handleChange(event.target.value)}
                      onBlur={field.handleBlur}
                      className={`${INPUT_CLASS} min-h-28 resize-y`}
                    />
                  )}
                </form.Field>
              </Field>

              <Field label="评分要点（每行一条）">
                <form.Field name="keyPoints">
                  {(field) => (
                    <textarea
                      value={field.state.value}
                      onChange={(event) => field.handleChange(event.target.value)}
                      onBlur={field.handleBlur}
                      className={`${INPUT_CLASS} min-h-20 resize-y`}
                    />
                  )}
                </form.Field>
              </Field>

              <Field label="评分规则">
                <form.Field name="scoringRubric">
                  {(field) => (
                    <textarea
                      value={field.state.value}
                      onChange={(event) => field.handleChange(event.target.value)}
                      onBlur={field.handleBlur}
                      className={`${INPUT_CLASS} min-h-20 resize-y`}
                    />
                  )}
                </form.Field>
              </Field>

              <Field label="追问（每行：题干 | 参考答案 | 要点1,要点2 | 评分规则）">
                <form.Field name="followUps">
                  {(field) => (
                    <textarea
                      value={field.state.value}
                      onChange={(event) => field.handleChange(event.target.value)}
                      onBlur={field.handleBlur}
                      className={`${INPUT_CLASS} min-h-24 resize-y`}
                    />
                  )}
                </form.Field>
              </Field>

              {error && <p className="text-sm text-red-500">{error}</p>}
            </div>

            <div className="sticky bottom-0 flex items-center justify-end gap-3 border-t border-slate-100 bg-white px-5 py-4 dark:border-slate-700 dark:bg-slate-900">
              <button
                type="button"
                onClick={onClose}
                className="rounded-lg border border-slate-200 px-4 py-2 text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
              >
                取消
              </button>
              <button
                type="submit"
                disabled={saving}
                className="inline-flex items-center gap-2 rounded-lg bg-primary-500 px-4 py-2 font-medium text-white hover:bg-primary-600 disabled:opacity-50"
              >
                {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <FileText className="h-4 w-4" />}
                保存
              </button>
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Field({label, children}: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-medium text-slate-700 dark:text-slate-200">{label}</span>
      {children}
    </label>
  );
}

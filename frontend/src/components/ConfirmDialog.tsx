import {Dialog} from '@base-ui/react/dialog';
import {motion} from 'framer-motion';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string | React.ReactNode;
  confirmText?: string;
  cancelText?: string;
  confirmVariant?: 'danger' | 'primary' | 'warning';
  onConfirm: () => void;
  onCancel: () => void;
  loading?: boolean;
  customContent?: React.ReactNode;
  hideButtons?: boolean;
}

/**
 * 通用确认弹窗。
 * 用 Base UI 的 Dialog 提供无障碍语义（焦点陷阱、Esc 关闭、role=dialog、标题/描述关联），
 * 视觉与动效仍由 Tailwind + framer-motion 控制（Base UI 是 headless 的）。
 */
export default function ConfirmDialog({
  open,
  title,
  message,
  confirmText = '确定',
  cancelText = '取消',
  confirmVariant = 'primary',
  onConfirm,
  onCancel,
  loading = false,
  customContent,
  hideButtons = false
}: ConfirmDialogProps) {
  const variantStyles = {
    danger: 'bg-gradient-to-r from-red-500 to-red-600 hover:from-red-600 hover:to-red-700',
    primary: 'bg-gradient-to-r from-primary-500 to-primary-600 hover:from-primary-600 hover:to-primary-700',
    warning: 'bg-gradient-to-r from-amber-500 to-orange-500 hover:from-amber-600 hover:to-orange-600'
  };

  return (
    <Dialog.Root
      open={open}
      // Base UI 的 onOpenChange 第二个参数是事件的 ChangeEventDetails，这里只需要 open 状态
      onOpenChange={(next) => {
        if (!next) onCancel();
      }}
    >
      <Dialog.Portal>
        <Dialog.Backdrop
          className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        />
        <Dialog.Popup
          className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-2xl outline-none dark:bg-slate-800"
        >
          <motion.div
            initial={{opacity: 0, scale: 0.95, y: 20}}
            animate={{opacity: 1, scale: 1, y: 0}}
            transition={{duration: 0.18}}
          >
            <Dialog.Title className="mb-4 text-xl font-bold text-slate-900 dark:text-white">
              {title}
            </Dialog.Title>

            <div className="mb-6 text-slate-600 dark:text-slate-300">
              {typeof message === 'string' ? (
                message && <p className="whitespace-pre-line">{message}</p>
              ) : (
                message
              )}
              {customContent}
            </div>

            {!hideButtons && (
              <div className="flex justify-end gap-3">
                <Dialog.Close
                  disabled={loading}
                  className="rounded-xl border border-slate-200 px-5 py-2.5 font-medium text-slate-600 transition-all hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
                >
                  {cancelText}
                </Dialog.Close>
                <button
                  type="button"
                  onClick={onConfirm}
                  disabled={loading}
                  className={`rounded-xl px-5 py-2.5 font-semibold text-white shadow-lg transition-all disabled:cursor-not-allowed disabled:opacity-50 ${variantStyles[confirmVariant]}`}
                >
                  {loading ? (
                    <span className="flex items-center gap-2">
                      <motion.span
                        className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white"
                        animate={{rotate: 360}}
                        transition={{duration: 1, repeat: Infinity, ease: 'linear'}}
                      />
                      处理中...
                    </span>
                  ) : (
                    confirmText
                  )}
                </button>
              </div>
            )}
          </motion.div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

import {Switch} from '@base-ui/react/switch'

interface AppSwitchProps {
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  ariaLabel?: string
  disabled?: boolean
  className?: string
}

/**
 * Base UI Switch 的项目内封装。
 * 无障碍语义（role=switch、键盘、aria-checked）由 Base UI 提供，视觉用 Tailwind。
 */
export function AppSwitch({checked, onCheckedChange, ariaLabel, disabled, className}: AppSwitchProps) {
  return (
    <Switch.Root
      checked={checked}
      onCheckedChange={onCheckedChange}
      aria-label={ariaLabel}
      disabled={disabled}
      className={className ?? 'relative inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full border-0 bg-slate-300 transition-colors data-[checked]:bg-primary-500 dark:bg-slate-600 dark:data-[checked]:bg-primary-500'}
    >
      <Switch.Thumb className="block h-4 w-4 translate-x-0.5 rounded-full bg-white transition-transform data-[checked]:translate-x-[1.125rem]" />
    </Switch.Root>
  )
}

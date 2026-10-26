import {Select} from '@base-ui/react/select'
import {Check, ChevronDown} from 'lucide-react'

export interface AppSelectOption {
  value: string
  label: string
  disabled?: boolean
}

interface AppSelectProps {
  value: string
  options: readonly AppSelectOption[]
  onChange: (value: string) => void
  placeholder?: string
  className?: string
  disabled?: boolean
  ariaLabel?: string
}

/**
 * Base UI Select 的项目内封装。
 * Base UI 是 headless 的，这里把 Tailwind 样式一次性收敛，避免每处 select 重复写
 * Root / Trigger / Value / Portal / Positioner / Popup / Item 的组合。
 */
export function AppSelect({value, options, onChange, placeholder, className, disabled, ariaLabel}: AppSelectProps) {
  return (
    <Select.Root value={value} onValueChange={(next) => onChange(String(next))} disabled={disabled}>
      <Select.Trigger
        aria-label={ariaLabel}
        className={className ?? 'flex w-full items-center justify-between gap-2 border border-slate-200 dark:border-slate-600 rounded-lg px-3 py-2 text-sm bg-white dark:bg-slate-800 text-slate-900 dark:text-white'}
      >
        <Select.Value placeholder={placeholder} />
        <Select.Icon>
          <ChevronDown className="w-4 h-4 opacity-60" />
        </Select.Icon>
      </Select.Trigger>
      <Select.Portal>
        <Select.Positioner sideOffset={4} className="z-[60]">
          <Select.Popup className="max-h-64 min-w-[var(--anchor-width)] overflow-y-auto rounded-lg border border-slate-200 bg-white py-1 shadow-lg dark:border-slate-600 dark:bg-slate-800">
            {options.map((option) => (
              <Select.Item
                key={option.value}
                value={option.value}
                disabled={option.disabled}
                className="flex cursor-pointer items-center justify-between gap-2 px-3 py-2 text-sm text-slate-700 outline-none select-none data-[highlighted]:bg-slate-100 dark:text-slate-200 dark:data-[highlighted]:bg-slate-700 data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50"
              >
                <Select.ItemText>{option.label}</Select.ItemText>
                <Select.ItemIndicator>
                  <Check className="w-4 h-4 text-primary-500" />
                </Select.ItemIndicator>
              </Select.Item>
            ))}
          </Select.Popup>
        </Select.Positioner>
      </Select.Portal>
    </Select.Root>
  )
}

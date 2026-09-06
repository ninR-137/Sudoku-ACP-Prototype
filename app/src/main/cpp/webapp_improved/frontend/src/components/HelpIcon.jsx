export default function HelpIcon({ className = '', onClick, label = 'Help' }) {
  return (
    <button
      type="button"
      className={['exp-help-icon', className].filter(Boolean).join(' ')}
      onClick={onClick}
      aria-label={label}
      title={label}
    >
      ?
    </button>
  )
}


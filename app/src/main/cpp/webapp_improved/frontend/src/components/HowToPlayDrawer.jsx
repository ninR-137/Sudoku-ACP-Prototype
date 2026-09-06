import { useEffect } from 'react'

function MiniBoard({ title, subtitle, variant = 'row' }) {
  const cells = Array.from({ length: 81 }, (_, i) => i)
  return (
    <div className="htp-card">
      <div className="htp-card__header">
        <div className="htp-card__badge" aria-hidden>{title}</div>
        <div className="htp-card__text">
          <div className="htp-card__title">{subtitle}</div>
        </div>
      </div>
      <div className={`htp-mini htp-mini--${variant}`} aria-hidden>
        {cells.map((i) => (
          <div key={i} className="htp-mini__cell" />
        ))}
      </div>
    </div>
  )
}

export default function HowToPlayDrawer({ open, onClose }) {
  useEffect(() => {
    if (!open) return
    const onKey = (e) => {
      if (e.key === 'Escape') onClose?.()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  return (
    <>
      <div className={`htp-drawer ${open ? 'open' : ''}`} role="dialog" aria-label="How to Play" aria-modal="true">
        <div className="htp-drawer__top">
          <h2>How to Play (Sudoku Rules)</h2>
          <button type="button" className="htp-close" onClick={onClose} aria-label="Close how to play">×</button>
        </div>

        <p className="htp-lede">
          Sudoku is a logic puzzle where each <b>row</b>, <b>column</b>, and <b>box</b> must contain every number exactly once.
        </p>

        <h3>Core rules</h3>
        <div className="htp-grid">
          <MiniBoard
            title="1"
            subtitle="Row rule — each row contains numbers 1 to N exactly once."
            variant="row"
          />
          <MiniBoard
            title="2"
            subtitle="Column rule — each column contains numbers 1 to N exactly once."
            variant="col"
          />
          <MiniBoard
            title="3"
            subtitle="Box rule — each 3×3 sub-box contains numbers 1 to 9 exactly once."
            variant="box"
          />
          <MiniBoard
            title="4"
            subtitle="Fixed clues — given clues can’t be changed."
            variant="fixed"
          />
          <MiniBoard
            title="5"
            subtitle="Logic over guessing — use deduction to solve."
            variant="logic"
          />
        </div>
      </div>

      <div
        className={`htp-backdrop ${open ? 'open' : ''}`}
        onClick={(e) => {
          if (e.target === e.currentTarget) onClose?.()
        }}
        aria-hidden
      />
    </>
  )
}


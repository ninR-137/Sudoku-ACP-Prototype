import { useEffect } from 'react'
import RuleCard from './RuleCard'
import MiniSudokuBoard from './MiniSudokuBoard'

function makeCells(pairs) {
  const cells = Array.from({ length: 81 }, () => ({ kind: 'empty', value: '' }))
  for (const p of pairs) {
    const idx = p.i
    if (typeof idx !== 'number' || idx < 0 || idx >= 81) continue
    const kind = p.kind === 'clue' || p.kind === 'user' ? p.kind : 'empty'
    const value = p.value == null ? '' : String(p.value)
    cells[idx] = { kind, value, error: Boolean(p.error) }
  }
  return cells
}

// Deterministic, realistic examples for teaching (fixed 9×9).
const ROW_RULE = makeCells([
  // highlighted row (r=3): show 1–9 once with one wrong duplicate as "user" error
  { i: 27 + 0, kind: 'clue', value: 8 },
  { i: 27 + 1, kind: 'user', value: 1 },
  { i: 27 + 2, kind: 'user', value: 2 },
  { i: 27 + 3, kind: 'clue', value: 3 },
  { i: 27 + 4, kind: 'user', value: 4 },
  { i: 27 + 5, kind: 'user', value: 5 },
  { i: 27 + 6, kind: 'user', value: 6 },
  { i: 27 + 7, kind: 'user', value: 6, error: true }, // duplicate "6" example
  { i: 27 + 8, kind: 'user', value: 9 },
  // a few surrounding clues for context
  { i: 2, kind: 'clue', value: 7 },
  { i: 10, kind: 'clue', value: 9 },
  { i: 20, kind: 'clue', value: 4 },
  { i: 36 + 4, kind: 'clue', value: 7 },
])

const COL_RULE = makeCells([
  // highlight col (c=4): valid distribution (no duplicates)
  { i: 0 * 9 + 4, kind: 'clue', value: 6 },
  { i: 1 * 9 + 4, kind: 'user', value: 1 },
  { i: 2 * 9 + 4, kind: 'user', value: 9 },
  { i: 3 * 9 + 4, kind: 'clue', value: 7 },
  { i: 4 * 9 + 4, kind: 'user', value: 5 },
  { i: 5 * 9 + 4, kind: 'user', value: 2 },
  { i: 6 * 9 + 4, kind: 'clue', value: 8 },
  { i: 7 * 9 + 4, kind: 'user', value: 3 },
  { i: 8 * 9 + 4, kind: 'user', value: 4 },
  // context
  { i: 1, kind: 'clue', value: 2 },
  { i: 8, kind: 'clue', value: 1 },
  { i: 7 * 9 + 7, kind: 'clue', value: 9 },
])

const BOX_RULE = makeCells([
  // highlight box index 4 (center box: rows 3–5, cols 3–5): uniqueness emphasized
  { i: 3 * 9 + 3, kind: 'clue', value: 4 },
  { i: 3 * 9 + 4, kind: 'user', value: 8 },
  { i: 3 * 9 + 5, kind: 'user', value: 1 },
  { i: 4 * 9 + 3, kind: 'user', value: 9 },
  { i: 4 * 9 + 4, kind: 'clue', value: 6 },
  { i: 4 * 9 + 5, kind: 'user', value: 2 },
  { i: 5 * 9 + 3, kind: 'user', value: 7 },
  { i: 5 * 9 + 4, kind: 'user', value: 5 },
  { i: 5 * 9 + 5, kind: 'clue', value: 3 },
  // context
  { i: 0, kind: 'clue', value: 5 },
  { i: 80, kind: 'clue', value: 8 },
])

const FIXED_CLUES = makeCells([
  // locked clues (scattered)
  { i: 0, kind: 'clue', value: 9 },
  { i: 4, kind: 'clue', value: 3 },
  { i: 8, kind: 'clue', value: 1 },
  { i: 10, kind: 'clue', value: 6 },
  { i: 20, kind: 'clue', value: 8 },
  { i: 30, kind: 'clue', value: 7 },
  { i: 40, kind: 'clue', value: 5 },
  { i: 60, kind: 'clue', value: 2 },
  { i: 72, kind: 'clue', value: 4 },
  // user inputs
  { i: 1, kind: 'user', value: 2 },
  { i: 2, kind: 'user', value: 7 },
  { i: 12, kind: 'user', value: 4 },
  { i: 13, kind: 'user', value: 9 },
  { i: 49, kind: 'user', value: 1 },
  { i: 50, kind: 'user', value: 6 },
])

export default function HowToPlayModal({ open, onClose }) {
  useEffect(() => {
    if (!open) return
    const onKey = (e) => {
      if (e.key === 'Escape') onClose?.()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      className="gm-modal-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose?.()
      }}
      role="presentation"
    >
      <section className="gm-modal gm-help-modal gm-help-modal--anim" role="dialog" aria-modal="true" aria-label="How to Play">
        <div className="gm-help-modal__top gm-help-modal__top--sticky">
          <div className="gm-help-modal__heading">
            <h3>How to Play</h3>
            <p className="gm-help-modal__sub">
              Learn the four core rules. Mini boards use <b>realistic</b> clues (locked), user inputs, and empty cells.
            </p>
          </div>
          <button type="button" className="gm-help-modal__close" onClick={onClose} aria-label="Close how to play">
            ×
          </button>
        </div>

        <div className="gm-help-modal__grid" role="list">
          <RuleCard
            number={1}
            title="Row Rule"
            description="Each row must contain the numbers 1–9 exactly once."
          >
            <MiniSudokuBoard
              label="Row rule example"
              cells={ROW_RULE}
              highlight={{ type: 'row', index: 3 }}
            />
            <div className="htpm-note">
              The highlighted row shows a <span className="htpm-note__warn">duplicate</span> (red) to illustrate a conflict.
            </div>
          </RuleCard>

          <RuleCard
            number={2}
            title="Column Rule"
            description="Each column must contain the numbers 1–9 exactly once."
          >
            <MiniSudokuBoard
              label="Column rule example"
              cells={COL_RULE}
              highlight={{ type: 'col', index: 4 }}
            />
            <div className="htpm-note">The highlighted column demonstrates a valid set (no repeats).</div>
          </RuleCard>

          <RuleCard
            number={3}
            title="Box Rule"
            description="Each 3×3 box must contain the numbers 1–9 exactly once."
          >
            <MiniSudokuBoard
              label="Box rule example"
              cells={BOX_RULE}
              highlight={{ type: 'box', index: 4 }}
            />
            <div className="htpm-note">Focus on the center 3×3 box—each number appears once.</div>
          </RuleCard>

          <RuleCard
            number={4}
            title="Fixed Clues"
            description="Yellow clues are locked. Fill the remaining cells with your own inputs."
          >
            <MiniSudokuBoard
              label="Fixed clues example"
              cells={FIXED_CLUES}
            />
            <div className="htpm-note">
              Try clicking cells: <b>clues</b> are disabled; user cells can be selected.
            </div>
          </RuleCard>
        </div>
      </section>
    </div>
  )
}


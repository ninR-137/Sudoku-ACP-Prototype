import { useMemo, useState } from 'react'

function boxIndex(r, c) {
  return Math.floor(r / 3) * 3 + Math.floor(c / 3)
}

function isInHighlightedRegion(r, c, highlight) {
  if (!highlight) return false
  if (highlight.type === 'row') return r === highlight.index
  if (highlight.type === 'col') return c === highlight.index
  if (highlight.type === 'box') return boxIndex(r, c) === highlight.index
  return false
}

/**
 * Strict 9×9 mini sudoku board.
 *
 * Cells: { value?: string|number, kind: 'clue'|'user'|'empty', error?: boolean }
 */
export default function MiniSudokuBoard({ cells, highlight, label }) {
  const [selected, setSelected] = useState(-1)

  const normalized = useMemo(() => {
    const safe = Array.from({ length: 81 }, (_, i) => {
      const cell = cells?.[i] || { kind: 'empty', value: '' }
      const kind = cell.kind === 'clue' || cell.kind === 'user' ? cell.kind : 'empty'
      const value = cell.value == null ? '' : String(cell.value)
      const error = Boolean(cell.error)
      return { kind, value, error }
    })
    return safe
  }, [cells])

  return (
    <div className="msb" aria-label={label || 'Mini sudoku board'}>
      {normalized.map((cell, i) => {
        const r = Math.floor(i / 9)
        const c = i % 9
        const isRegion = isInHighlightedRegion(r, c, highlight)
        const isSelected = selected === i
        const disabled = cell.kind === 'clue'

        return (
          <button
            key={i}
            type="button"
            className={[
              'msb__cell',
              `k-${cell.kind}`,
              cell.error ? 'is-err' : '',
              isRegion ? 'is-hl' : '',
              isSelected ? 'is-sel' : '',
              disabled ? 'is-locked' : '',
            ]
              .filter(Boolean)
              .join(' ')}
            onClick={() => {
              if (disabled) return
              setSelected((prev) => (prev === i ? -1 : i))
            }}
            disabled={disabled}
            aria-disabled={disabled}
            aria-label={`Cell ${r + 1},${c + 1}`}
          >
            <span className="msb__val">{cell.value}</span>
          </button>
        )
      })}
    </div>
  )
}


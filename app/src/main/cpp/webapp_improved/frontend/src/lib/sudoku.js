export const SIZES = { '9×9': 3, '16×16': 4, '25×25': 5 }
export const CELLS = { 3: 81, 4: 256, 5: 625 }
export const CREATED_KEY = 'sudophaseCreatedPuzzles'

export function order4CharToDisplay(ch) {
  if (ch === '.') return ''
  if (ch >= '0' && ch <= '9') return String(ch.charCodeAt(0) - 48 + 1)
  if (ch >= 'a' && ch <= 'f') return String(ch.charCodeAt(0) - 97 + 11)
  return ''
}

export function order5CharToDisplay(ch) {
  if (ch === '.') return ''
  if (ch >= 'a' && ch <= 'y') return String(ch.charCodeAt(0) - 97 + 1)
  return ''
}

export function order4DisplayToChar(v) {
  const n = parseInt(v, 10)
  if (Number.isNaN(n)) return '.'
  if (n >= 1 && n <= 10) return String.fromCharCode(48 + n - 1)
  if (n >= 11 && n <= 16) return String.fromCharCode(97 + n - 11)
  return '.'
}

export function order5DisplayToChar(v) {
  const n = parseInt(v, 10)
  if (Number.isNaN(n)) return '.'
  if (n >= 1 && n <= 25) return String.fromCharCode(97 + n - 1)
  return '.'
}

export function puzzleToDisplayValues(puzzle, order) {
  const n = order * order
  const len = n * n
  const str = String(puzzle || '').padEnd(len, '.').slice(0, len)
  return Array.from({ length: len }, (_, i) => {
    const ch = str[i]
    if (order === 3) return ch !== '.' && /[1-9]/.test(ch) ? ch : ''
    if (order === 4) return order4CharToDisplay(ch)
    return order5CharToDisplay(ch)
  })
}

/**
 * Merge polling `best_solution` snapshots for live solve UI.
 *
 * - **Truncated** `raw` (length &lt; grid): keep prior cells beyond the string;
 *   monotonic merge inside the prefix so padding does not blank the tail.
 * - **Full-length** `raw`: usually trust a **strict** snapshot (solver state).
 *   Use **monotonic** merge only when the snapshot is not a strict improvement
 *   and we are not fixing a "phantom full" grid (UI had every cell filled but
 *   the latest snapshot still has gaps — monotonic stacking from mixed polls).
 *
 * Final `status === 'done'` still applies `solution` with a full replace.
 */
export function mergeBestSolutionDisplay(prevValues, bestSolutionRaw, order, fixedSet, initialPuzzle) {
  const len = CELLS[order]
  const raw = String(bestSolutionRaw || '')
  const clueVals = puzzleToDisplayValues(initialPuzzle, order)
  const padded = raw.padEnd(len, '.').slice(0, len)
  const incoming = puzzleToDisplayValues(padded, order)
  const prevSafe = Array.from({ length: len }, (_, i) => {
    const v = prevValues?.[i]
    return v == null ? '' : String(v).trim()
  })

  const countFilled = (arr) => arr.reduce((a, v) => a + (String(v || '').trim() ? 1 : 0), 0)

  const build = (monotonic) =>
    Array.from({ length: len }, (_, i) => {
      if (fixedSet.has(i)) return clueVals[i]
      const inc = String(incoming[i] || '').trim()
      const prev = prevSafe[i]
      if (i >= raw.length) return prev
      if (monotonic && !inc && prev) return prev
      return inc
    })

  if (raw.length < len) {
    return build(true)
  }

  const incFilled = countFilled(incoming)
  const prevFilled = countFilled(prevSafe)

  if (incFilled > prevFilled) {
    return build(false)
  }
  if (prevFilled === len && incFilled < len) {
    return build(false)
  }
  return build(true)
}

export function displayValuesToPuzzle(values, order) {
  const out = values.map((v) => {
    const val = String(v || '').trim()
    if (!val) return '.'
    if (order === 3) return /^[1-9]$/.test(val) ? val : '.'
    if (order === 4) return order4DisplayToChar(val)
    return order5DisplayToChar(val)
  })
  return out.join('')
}

export function parseInstanceText(text) {
  const rawLines = String(text || '').split(/\r?\n/).map((l) => l.trim())
  const lines = rawLines.filter((l) => l && l.charAt(0) !== '#')
  if (lines.length < 2) throw new Error('Invalid file: need order and idum lines.')
  const order = parseInt(lines[0], 10)
  if (![3, 4, 5].includes(order)) throw new Error('Unsupported order; use 3, 4, or 5.')
  const numCells = Math.pow(order, 4)
  const values = []
  for (let i = 2; i < lines.length; i += 1) {
    lines[i].split(/\s+/).forEach((part) => {
      const n = parseInt(part, 10)
      if (!Number.isNaN(n)) values.push(n)
    })
  }
  if (values.length < numCells) throw new Error(`Not enough grid values (expected ${numCells}).`)
  const slice = values.slice(0, numCells)
  const chars = slice.map((v) => {
    if (v === -1) return '.'
    if (order === 3) return (v >= 1 && v <= 9) ? String.fromCharCode(48 + v) : '.'
    if (order === 4) {
      if (v >= 1 && v <= 10) return String.fromCharCode(48 + v - 1)
      if (v >= 11 && v <= 16) return String.fromCharCode(97 + v - 11)
      return '.'
    }
    return (v >= 1 && v <= 25) ? String.fromCharCode(97 + v - 1) : '.'
  })
  return { order, puzzle: chars.join('') }
}

export function getCreatedPuzzlesByOrder(order) {
  const raw = localStorage.getItem(CREATED_KEY)
  let list = []
  try {
    list = raw ? JSON.parse(raw) : []
  } catch {
    list = []
  }
  if (!Array.isArray(list)) return []
  const want = Number(order)
  if (!Number.isFinite(want) || want < 3 || want > 5) return []
  return list
    .filter((p) => {
      if (!p || typeof p.puzzle !== 'string') return false
      const o = Number(p.order)
      return Number.isFinite(o) && o === want
    })
    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
    .map((p, idx) => ({
      ...p,
      order: want,
      id:
        typeof p.id === 'string' && p.id
          ? p.id
          : `c-legacy-${idx}-${p.createdAt ?? 0}`,
    }))
}

/** True if encoded puzzle string has at least one clue (not '.'). */
export function puzzleStringHasClue(puzzle) {
  return /[^.]/.test(String(puzzle || ''))
}

/** Add a puzzle to the "Created puzzles" list in localStorage (Game / Experiment pickers). */
export function appendCreatedPuzzle({ order, puzzle, name }) {
  if (![3, 4, 5].includes(order) || typeof puzzle !== 'string') return
  if (!puzzleStringHasClue(puzzle)) return
  const raw = localStorage.getItem(CREATED_KEY)
  let list = []
  try {
    list = raw ? JSON.parse(raw) : []
  } catch {
    list = []
  }
  if (!Array.isArray(list)) list = []
  const id = `c-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  list.push({
    id,
    order,
    puzzle,
    name: String(name || 'Created puzzle').trim() || 'Created puzzle',
    createdAt: Date.now(),
  })
  localStorage.setItem(CREATED_KEY, JSON.stringify(list))
}

export function sanitizeDownloadBase(name, fallback) {
  const s = String(name || '').trim().replace(/[<>:"/\\|?*\x00-\x1F]/g, '_')
  return s || fallback
}

export function computeConflictSet(values, order) {
  const n = order * order
  const total = n * n
  const out = new Set()
  const vals = Array.from({ length: total }, (_, i) => String(values?.[i] ?? '').trim())

  const markDupGroup = (indices) => {
    const seen = new Map()
    for (const idx of indices) {
      const v = vals[idx]
      if (!v) continue
      const list = seen.get(v) || []
      list.push(idx)
      seen.set(v, list)
    }
    for (const list of seen.values()) {
      if (list.length > 1) list.forEach((i) => out.add(i))
    }
  }

  // rows
  for (let r = 0; r < n; r += 1) {
    const row = []
    for (let c = 0; c < n; c += 1) row.push(r * n + c)
    markDupGroup(row)
  }
  // cols
  for (let c = 0; c < n; c += 1) {
    const col = []
    for (let r = 0; r < n; r += 1) col.push(r * n + c)
    markDupGroup(col)
  }
  // boxes
  for (let br = 0; br < n; br += order) {
    for (let bc = 0; bc < n; bc += order) {
      const box = []
      for (let r = 0; r < order; r += 1) {
        for (let c = 0; c < order; c += 1) {
          box.push((br + r) * n + (bc + c))
        }
      }
      markDupGroup(box)
    }
  }
  return out
}

import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import SudokuBoardCanvas from '../components/SudokuBoardCanvas'
import ModePopSquares from '../components/ModePopSquares'
import {
  appendCreatedPuzzle,
  CELLS,
  computeConflictSet,
  displayValuesToPuzzle,
  puzzleStringHasClue,
  sanitizeDownloadBase,
} from '../lib/sudoku'

const ACCEPTED_FORMAT = `Plain text, UTF-8. Lines that start with # are treated as comments and ignored.\n\n<order>       -> 3 = 9×9, 4 = 16×16, 5 = 25×25\n<idum>        -> integer (often 0), ignored by the web app\n<n² rows of n² integers> -> -1 means empty cell\n\nExample (order 3):\n3\n0\n-1 -1 -1 -1 -1 -1 -1 -1 -1\n...`

export default function CreatePuzzlePage() {
  const nav = useNavigate()
  const [order, setOrder] = useState(3)
  const [values, setValues] = useState(Array(81).fill(''))
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [name, setName] = useState('')
  const [passSessionPuzzle, setPassSessionPuzzle] = useState(true)
  const [formatOpen, setFormatOpen] = useState(false)
  const [successModalOpen, setSuccessModalOpen] = useState(false)
  const [saveModalOpen, setSaveModalOpen] = useState(false)
  const [saveFmt, setSaveFmt] = useState('txt')
  const [footerError, setFooterError] = useState('')

  const n = order * order
  const fixedSet = useMemo(() => new Set(), [])
  const normalizedValues = useMemo(() => {
    const len = CELLS[order]
    return Array.from({ length: len }, (_, i) => String(values?.[i] ?? ''))
  }, [values, order])

  const conflictSet = useMemo(
    () => computeConflictSet(normalizedValues, order),
    [normalizedValues, order],
  )

  function setDigit(v) {
    const next = [...normalizedValues]
    next[selectedIndex] = String(v)
    setValues(next)
  }

  function del() {
    const next = [...normalizedValues]
    next[selectedIndex] = ''
    setValues(next)
  }

  function handleBoardKey(i, key) {
    if (key === '') {
      const next = [...normalizedValues]
      next[i] = ''
      setValues(next)
      return
    }
    const maxVal = n
    let v = String(key).replace(/\D/g, '')
    if (maxVal <= 9) v = v.slice(0, 1)
    else {
      const cur = String(normalizedValues[i] || '').replace(/\D/g, '')
      v = (cur + v).slice(0, 2)
      const num = parseInt(v, 10)
      if (!Number.isNaN(num) && num > maxVal) v = String(maxVal)
    }
    const next = [...normalizedValues]
    next[i] = v
    setValues(next)
  }

  function puzzleString() {
    return displayValuesToPuzzle(normalizedValues, order)
  }

  function validateNameAndPuzzle() {
    const displayName = String(name || '').trim()
    if (!displayName) {
      setFooterError('Please enter a puzzle name.')
      return null
    }
    const p = puzzleString()
    if (!puzzleStringHasClue(p)) {
      setFooterError('Add at least one clue before continuing.')
      return null
    }
    if (conflictSet.size > 0) {
      setFooterError(
        'The puzzle has duplicate values in a row, column, or block. Fix the errors before saving or creating.',
      )
      return null
    }
    setFooterError('')
    return { displayName, puzzle: p }
  }

  function triggerBlobDownload(blob, filename) {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  function handleCreate() {
    const v = validateNameAndPuzzle()
    if (!v) return
    appendCreatedPuzzle({ order, puzzle: v.puzzle, name: v.displayName })
    setSuccessModalOpen(true)
  }

  function handleOpenSaveModal() {
    const v = validateNameAndPuzzle()
    if (!v) return
    setSaveFmt('txt')
    setSaveModalOpen(true)
  }

  async function runSaveExport() {
    const displayName = String(name || '').trim()
    if (!displayName) {
      setFooterError('Please enter a puzzle name.')
      return
    }
    const p = puzzleString()
    if (!puzzleStringHasClue(p)) {
      setFooterError('Add at least one clue before saving.')
      return
    }
    if (computeConflictSet(normalizedValues, order).size > 0) {
      setFooterError(
        'The puzzle has duplicate values in a row, column, or block. Fix the errors before saving.',
      )
      return
    }
    setFooterError('')
    const base = sanitizeDownloadBase(displayName.replace(/\s+/g, '_'), 'puzzle')
    const ext = saveFmt === 'pdf' ? '.pdf' : '.txt'
    const filename = base.toLowerCase().endsWith(ext) ? base : base + ext
    const endpoint = saveFmt === 'pdf' ? '/api/pdf' : '/api/export/report-txt'
    const body = {
      reportKind: 'initial',
      includeParams: false,
      order,
      puzzle: p,
      currentPuzzle: '',
      solution: '',
      params: {},
    }
    try {
      const r = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!r.ok) {
        const t = await r.text().catch(() => '')
        setFooterError(t ? `Save failed: ${t}` : 'Save failed.')
        return
      }
      triggerBlobDownload(await r.blob(), filename)
      setSaveModalOpen(false)
    } catch (e) {
      setFooterError(e.message || 'Network error while saving.')
    }
  }

  function goPlayAfterCreate(path) {
    const displayName = String(name || '').trim()
    const p = puzzleString()
    if (!displayName || !puzzleStringHasClue(p)) return
    const payload = { order, puzzle: p, name: displayName }
    if (passSessionPuzzle) sessionStorage.setItem('createdPuzzle', JSON.stringify(payload))
    setSuccessModalOpen(false)
    nav(path)
  }

  const digits = useMemo(() => Array.from({ length: n }, (_, i) => i + 1), [n])

  function renderKeypad() {
    const digitBtn = (d) => (
      <button key={d} type="button" className="gm-digit cp-keypad-digit" onClick={() => setDigit(d)}>
        {d}
      </button>
    )

    if (order === 3) {
      return (
        <div className="cp-keypad-row cp-keypad-row--nine">
          {digits.map(digitBtn)}
          <button type="button" className="cp-del cp-keypad-del" onClick={del}>Del</button>
        </div>
      )
    }

    if (order === 4) {
      return (
        <>
          <div className="cp-keypad-row cp-keypad-row--wrap">{digits.slice(0, 8).map(digitBtn)}</div>
          <div className="cp-keypad-row cp-keypad-row--wrap">
            {digits.slice(8).map(digitBtn)}
            <button type="button" className="cp-del cp-keypad-del" onClick={del}>Del</button>
          </div>
        </>
      )
    }

    return (
      <>
        <div className="cp-keypad-row cp-keypad-row--wrap">{digits.slice(0, 9).map(digitBtn)}</div>
        <div className="cp-keypad-row cp-keypad-row--wrap">{digits.slice(9, 18).map(digitBtn)}</div>
        <div className="cp-keypad-row cp-keypad-row--wrap">
          {digits.slice(18).map(digitBtn)}
          <button type="button" className="cp-del cp-keypad-del" onClick={del}>Del</button>
        </div>
      </>
    )
  }

  return (
    <main className="cp-page">
      <ModePopSquares count={56} seed={5006} />
      <Link to="/puzzles" className="puz-return-fab" aria-label="Return to Puzzles" title="Return to Puzzles">
        ↩
      </Link>
      <Link to="/" className="puz-home-fab" aria-label="Home" title="Home">
        ⌂
      </Link>

      <div className="cp-col">
        <h1 className="cp-title">Create Puzzle</h1>

        <div className="cp-controls">
          <div className="cp-sizes">
            <div className="cp-label">Choose size:</div>
            <button type="button" className={`cp-size ${order === 3 ? 'on' : ''}`} onClick={() => { setOrder(3); setValues(Array(81).fill('')); }}>9×9</button>
            <button type="button" className={`cp-size ${order === 4 ? 'on' : ''}`} onClick={() => { setOrder(4); setValues(Array(256).fill('')); }}>16×16</button>
            <button type="button" className={`cp-size ${order === 5 ? 'on' : ''}`} onClick={() => { setOrder(5); setValues(Array(625).fill('')); }}>25×25</button>
          </div>

          <button type="button" className="cp-accordion" onClick={() => setFormatOpen((x) => !x)}>
            {formatOpen ? '▼' : '▶'} Accepted puzzle file format (.txt)
          </button>
          {formatOpen ? <pre className="cp-format">{ACCEPTED_FORMAT}</pre> : null}
        </div>

        <p className="cp-hint">
          Fill in the clues (empty cells will be for the player to solve).
        </p>

        <div className="cp-board">
          <div className="gm-grid-wrap">
            <SudokuBoardCanvas
              order={order}
              cellValues={normalizedValues}
              fixedSet={fixedSet}
              conflictSet={conflictSet}
              selectedIndex={selectedIndex}
              onSelectCell={setSelectedIndex}
              onKeyInput={handleBoardKey}
            />
          </div>
        </div>

        <div className="cp-keypad-wrap" aria-label="Digit keypad">
          <div className="cp-keypad">{renderKeypad()}</div>
        </div>
      </div>

      <div className="cp-footer">
        <label className="cp-check">
          <input type="checkbox" checked={passSessionPuzzle} onChange={(e) => setPassSessionPuzzle(e.target.checked)} />
          Pass puzzle to Game/Experiment (this browser session only)
        </label>

        <div className="cp-bottom-row">
          <input
            className="cp-name"
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              if (footerError) setFooterError('')
            }}
            placeholder="Puzzle name (required)"
            autoComplete="off"
            aria-invalid={footerError ? 'true' : 'false'}
          />
          <button type="button" className="cp-btn" onClick={handleOpenSaveModal}>Save</button>
          <button type="button" className="cp-btn primary" onClick={handleCreate}>Create</button>
        </div>
        {footerError ? <p className="cp-footer-error" role="alert">{footerError}</p> : null}
      </div>

      {successModalOpen ? (
        <div
          className="gm-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget) setSuccessModalOpen(false)
          }}
        >
          <section className="gm-modal cp-success-modal">
            <h3>Successfully created puzzle</h3>
            <p className="cp-success-lede">Your puzzle was saved to Created puzzles.</p>
            <div className="cp-modal-actions cp-modal-actions--triple">
              <button type="button" className="gm-btn primary" onClick={() => goPlayAfterCreate('/game')}>
                Play in Game Mode
              </button>
              <button type="button" className="gm-btn primary" onClick={() => goPlayAfterCreate('/play')}>
                Play in Experiment Mode
              </button>
              <button type="button" className="gm-btn" onClick={() => setSuccessModalOpen(false)}>
                Go back
              </button>
            </div>
          </section>
        </div>
      ) : null}

      {saveModalOpen ? (
        <div
          className="gm-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget) setSaveModalOpen(false)
          }}
        >
          <section className="gm-modal cp-save-modal">
            <h3>Save</h3>
            <fieldset className="gm-fieldset">
              <legend>Document type</legend>
              <label>
                <input type="radio" name="cp-save-fmt" checked={saveFmt === 'txt'} onChange={() => setSaveFmt('txt')} />
                {' '}
                Text (.txt)
              </label>
              <label>
                <input type="radio" name="cp-save-fmt" checked={saveFmt === 'pdf'} onChange={() => setSaveFmt('pdf')} />
                {' '}
                PDF (.pdf)
              </label>
            </fieldset>
            <div className="gm-modal-actions">
              <button type="button" className="gm-btn" onClick={() => setSaveModalOpen(false)}>
                Cancel
              </button>
              <button type="button" className="gm-btn primary" onClick={() => void runSaveExport()}>
                Save
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </main>
  )
}


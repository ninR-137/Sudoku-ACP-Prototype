import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import SudokuBoardCanvas from '../components/SudokuBoardCanvas'
import ModePopSquares from '../components/ModePopSquares'
import HowToPlayModal from '../components/HowToPlayModal'
import {
  CELLS,
  SIZES,
  computeConflictSet,
  displayValuesToPuzzle,
  getCreatedPuzzlesByOrder,
  mergeBestSolutionDisplay,
  parseInstanceText,
  puzzleToDisplayValues,
  sanitizeDownloadBase,
} from '../lib/sudoku'

// Game Mode should use the backend solver defaults (do not override via API).
const GAME_SOLVE_TIMEOUT_DEFAULT = 180

function sizeLabelFromOrder(order) {
  return order === 3 ? '9×9' : order === 4 ? '16×16' : '25×25'
}

export default function GameModePage() {
  const [order, setOrder] = useState(3)
  const [values, setValues] = useState(Array(81).fill(''))
  const [initialPuzzle, setInitialPuzzle] = useState('.'.repeat(81))
  const [puzzleName, setPuzzleName] = useState('Game mode')
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [resultText, setResultText] = useState('')
  const [resultError, setResultError] = useState(false)
  const [solvedOpen, setSolvedOpen] = useState(false)
  const [solvedMsg, setSolvedMsg] = useState('')
  const [helpOpen, setHelpOpen] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [downloadOpen, setDownloadOpen] = useState(false)
  const [filenameOpen, setFilenameOpen] = useState(false)
  const [filenameDraft, setFilenameDraft] = useState('')
  const [pickerSource, setPickerSource] = useState('curated')
  const [pickerSize, setPickerSize] = useState('9×9')
  const [pickerSelected, setPickerSelected] = useState('')
  const [library, setLibrary] = useState({})
  const [solving, setSolving] = useState(false)
  const [activeJobId, setActiveJobId] = useState(null)
  const [lastSolvedParams, setLastSolvedParams] = useState(null)
  const [timerStart, setTimerStart] = useState(Date.now())
  const [timerTick, setTimerTick] = useState(0)
  const [dlKind, setDlKind] = useState('initial')
  const [dlFmt, setDlFmt] = useState('txt')
  const fileInputRef = useRef(null)
  const historyRef = useRef([])
  /** Prefetched `/api/instance` payloads so Load can apply without waiting on the network. */
  const curatedInstanceCacheRef = useRef(new Map())
  const [historyTick, setHistoryTick] = useState(0)

  const n = order * order
  const normalizedValues = useMemo(() => {
    const len = CELLS[order]
    return Array.from({ length: len }, (_, i) => {
      const v = values?.[i]
      return v == null ? '' : String(v)
    })
  }, [values, order])
  useEffect(() => {
    const id = setInterval(() => setTimerTick((x) => x + 1), 1000)
    return () => clearInterval(id)
  }, [])

  useEffect(() => {
    fetch('/api/library')
      .then((r) => r.json())
      .then((d) => setLibrary(d || {}))
      .catch(() => setLibrary({}))
  }, [])

  useEffect(() => {
    const created = sessionStorage.getItem('createdPuzzle')
    if (!created) return
    try {
      const data = JSON.parse(created)
      sessionStorage.removeItem('createdPuzzle')
      if (data?.order && data?.puzzle) {
        applyLoadedPuzzle(data.order, data.puzzle, data.name || 'Created puzzle')
      }
    } catch {
      // ignore invalid data
    }
  }, [])

  const pickerItems = useMemo(() => {
    if (!pickerOpen) return []
    const ord = SIZES[pickerSize] || 3
    if (pickerSource === 'curated') return library[pickerSize] || []
    if (pickerSource === 'created') {
      return getCreatedPuzzlesByOrder(ord).map((x) => ({ ...x, path: x.id }))
    }
    return []
  }, [pickerOpen, pickerSource, pickerSize, library])

  useEffect(() => {
    if (!pickerOpen) return
    setPickerSelected('')
  }, [pickerOpen, pickerSource, pickerSize, library])

  useEffect(() => {
    if (!pickerOpen || pickerSource !== 'curated' || !pickerSelected) return
    if (curatedInstanceCacheRef.current.has(pickerSelected)) return
    let cancelled = false
    fetch(`/api/instance/${encodeURIComponent(pickerSelected)}`)
      .then((r) => r.json().then((d) => ({ r, d })))
      .then(({ r, d }) => {
        if (cancelled || !r.ok || !d || typeof d.puzzle !== 'string') return
        curatedInstanceCacheRef.current.set(pickerSelected, { order: d.order, puzzle: d.puzzle })
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [pickerOpen, pickerSource, pickerSelected])

  const elapsed = useMemo(() => Math.max(0, Math.floor((Date.now() - timerStart) / 1000)), [timerStart, timerTick])

  const fixedSet = useMemo(() => {
    const s = new Set()
    const str = (initialPuzzle || '').padEnd(CELLS[order], '.')
    for (let i = 0; i < str.length; i += 1) {
      if (str[i] !== '.') s.add(i)
    }
    return s
  }, [initialPuzzle, order])

  const conflictSet = useMemo(() => computeConflictSet(normalizedValues, order), [normalizedValues, order])
  const canUndo = historyRef.current.length > 0

  function pushHistory(prev) {
    historyRef.current.push(prev)
    if (historyRef.current.length > 250) historyRef.current.shift()
    setHistoryTick((x) => x + 1)
  }

  function undo() {
    const prev = historyRef.current.pop()
    if (!prev) return
    setValues(prev)
    setHistoryTick((x) => x + 1)
  }

  const gridLooksComplete = useMemo(() => {
    const len = CELLS[order]
    return normalizedValues.length === len && normalizedValues.every((v) => String(v || '').trim() !== '')
  }, [normalizedValues, order])

  useEffect(() => {
    if (!solving) return
    setResultText((t) => {
      if (t !== 'Solving…' && t !== 'Checking solution…') return t
      return gridLooksComplete ? 'Checking solution…' : 'Solving…'
    })
  }, [solving, gridLooksComplete])

  function applyLoadedPuzzle(nextOrder, puzzle, name) {
    const nextValues = puzzleToDisplayValues(puzzle, nextOrder)
    setOrder(nextOrder)
    setValues(nextValues)
    setInitialPuzzle(puzzle)
    setPuzzleName(name || 'Puzzle')
    setSelectedIndex(0)
    setResultText('')
    setResultError(false)
    setLastSolvedParams(null)
    setTimerStart(Date.now())
    historyRef.current = []
    setHistoryTick((x) => x + 1)
  }

  function formatTime(sec) {
    const m = Math.floor(sec / 60)
    const s = sec % 60
    return `${m}:${s < 10 ? '0' : ''}${s}`
  }

  function handleInput(i, raw) {
    if (fixedSet.has(i)) return
    const maxVal = n
    let v = raw.replace(/\D/g, '')
    if (maxVal <= 9) v = v.slice(0, 1)
    else {
      const num = parseInt(v, 10)
      if (!Number.isNaN(num) && num > maxVal) v = String(maxVal)
      if (v.length > 2) v = v.slice(0, 2)
    }
    const next = [...normalizedValues]
    next[i] = v
    pushHistory([...normalizedValues])
    setValues(next)
  }

  function handleBoardKey(i, key) {
    if (fixedSet.has(i)) return
    if (key === '') {
      handleInput(i, '')
      return
    }
    if (n <= 9) {
      if (/^[1-9]$/.test(key)) handleInput(i, key)
      return
    }
    const cur = String(normalizedValues[i] || '').replace(/\D/g, '')
    if (!/^\d$/.test(key)) return
    handleInput(i, (cur + key).slice(0, 2))
  }

  function setDigit(v) {
    if (fixedSet.has(selectedIndex)) return
    const next = [...normalizedValues]
    next[selectedIndex] = String(v)
    pushHistory([...normalizedValues])
    setValues(next)
  }

  function eraseSelected() {
    if (fixedSet.has(selectedIndex)) return
    const next = [...normalizedValues]
    next[selectedIndex] = ''
    pushHistory([...normalizedValues])
    setValues(next)
  }

  function clearBoard() {
    // Clear ONLY user-entered values; keep fixed clues
    const len = CELLS[order]
    const prev = [...normalizedValues]
    const next = Array.from({ length: len }, (_, i) => (fixedSet.has(i) ? prev[i] : ''))
    pushHistory(prev)
    setValues(next)
    setResultText('')
    setResultError(false)
  }

  async function pickLoad() {
    if (pickerSource === 'upload') {
      fileInputRef.current?.click()
      return
    }
    if (!pickerSelected) return
    try {
      if (pickerSource === 'created') {
        const list = getCreatedPuzzlesByOrder(SIZES[pickerSize] || 3)
        const item = list.find((x) => String(x.id) === String(pickerSelected))
        if (!item) return
        applyLoadedPuzzle(item.order, item.puzzle, item.name || 'Created puzzle')
      } else {
        const cached = curatedInstanceCacheRef.current.get(pickerSelected)
        if (cached?.order != null && typeof cached.puzzle === 'string') {
          applyLoadedPuzzle(cached.order, cached.puzzle, (pickerSelected || '').split('/').pop() || 'Puzzle')
        } else {
          const r = await fetch(`/api/instance/${encodeURIComponent(pickerSelected)}`)
          const d = await r.json()
          if (!r.ok) throw new Error(d.error || 'Load failed')
          curatedInstanceCacheRef.current.set(pickerSelected, { order: d.order, puzzle: d.puzzle })
          applyLoadedPuzzle(d.order, d.puzzle, (pickerSelected || '').split('/').pop() || 'Puzzle')
        }
      }
      setPickerOpen(false)
    } catch (e) {
      setResultError(true)
      setResultText(e.message || 'Failed to load puzzle')
    }
  }

  function onUploadFile(file) {
    const reader = new FileReader()
    reader.onload = () => {
      try {
        const parsed = parseInstanceText(String(reader.result || ''))
        applyLoadedPuzzle(parsed.order, parsed.puzzle, (file.name || 'Uploaded').replace(/\.txt$/i, ''))
        setPickerOpen(false)
      } catch (e) {
        setResultError(true)
        setResultText(e.message || 'Failed to parse file')
      }
    }
    reader.readAsText(file, 'utf-8')
  }

  async function solvePuzzle() {
    const puzzle = initialPuzzle
    if (!puzzle.includes('.')) {
      setResultError(true)
      setResultText('Grid is already full')
      return
    }
    // Reset to clues only so a second Solve does not start from a full/solution grid.
    setValues(puzzleToDisplayValues(initialPuzzle, order))
    setLastSolvedParams(null)
    historyRef.current = []
    setHistoryTick((x) => x + 1)
    setSolving(true)
    setResultError(false)
    setResultText('Solving…')
    try {
      const postRes = await fetch('/api/solve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ puzzle, order }),
      })
      const postData = await postRes.json().catch(() => ({}))
      if (!postRes.ok || !postData.job_id) {
        throw new Error(postData.error || 'Failed to start solver')
      }
      setActiveJobId(postData.job_id)
      const deadline = Date.now() + (GAME_SOLVE_TIMEOUT_DEFAULT + 20) * 1000
      while (true) {
        if (Date.now() > deadline) throw new Error('Request timed out')
        const r = await fetch(`/api/status/${encodeURIComponent(postData.job_id)}`)
        const data = await r.json().catch(() => ({}))
        if (!r.ok || data.error) throw new Error(data.error || 'Status error')
        if (data.status === 'done' || data.status === 'error') {
          const res = data.result || {}
          if (res.success && res.solution) {
            setValues(puzzleToDisplayValues(res.solution, order))
            setLastSolvedParams(null)
            setResultError(false)
            const t = res.time?.toFixed?.(2) ?? '?'
            setResultText(`Solved in ${t} s`)
            setSolvedMsg(`Puzzle solved at ${t} s`)
            setSolvedOpen(true)
          } else {
            setResultError(true)
            setResultText(res.error || 'No solution within timeout')
          }
          break
        }
        if (data.best_solution) {
          setValues((prev) =>
            mergeBestSolutionDisplay(prev, data.best_solution, order, fixedSet, initialPuzzle),
          )
        }
        // eslint-disable-next-line no-await-in-loop
        await new Promise((resolve) => setTimeout(resolve, 150))
      }
    } catch (e) {
      setResultError(true)
      setResultText(e.message || 'Network error')
    } finally {
      setSolving(false)
      setActiveJobId(null)
    }
  }

  async function stopSolve() {
    if (!activeJobId) return
    try {
      await fetch(`/api/cancel/${encodeURIComponent(activeJobId)}`, { method: 'POST' })
      setResultError(true)
      setResultText('Puzzle not solved (cancelled).')
    } catch (e) {
      setResultError(true)
      setResultText(e.message || 'Cancel failed')
    } finally {
      setSolving(false)
      setActiveJobId(null)
    }
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

  async function runDownload() {
    const len = CELLS[order]
    if (!initialPuzzle || initialPuzzle === '.'.repeat(len)) {
      setResultError(true)
      setResultText('Load a puzzle first (not an empty grid).')
      return false
    }
    const current = displayValuesToPuzzle(normalizedValues, order)
    const solvedManual = normalizedValues.every((v) => String(v || '').trim() !== '')
    const includeParams = solvedManual && !!lastSolvedParams
    const reportKind = dlKind === 'initial' ? 'initial' : (solvedManual ? 'solved' : 'progress')
    const ext = dlFmt === 'pdf' ? '.pdf' : '.txt'
    const defaultName = sanitizeDownloadBase((puzzleName || 'puzzle').replace(/\s+/g, '_') + (dlKind === 'progress' ? '_progress' : ''), 'puzzle')
    const base = sanitizeDownloadBase(filenameDraft, defaultName)
    const filename = base.toLowerCase().endsWith(ext) ? base : base + ext
    const endpoint = dlFmt === 'pdf' ? '/api/pdf' : '/api/export/report-txt'
    const body = {
      reportKind,
      includeParams: dlKind === 'initial' ? false : includeParams,
      order,
      puzzle: initialPuzzle,
      currentPuzzle: dlKind === 'initial' ? '' : current,
      solution: reportKind === 'solved' ? current : '',
      params: includeParams ? (lastSolvedParams || {}) : {},
    }
    const r = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!r.ok) {
      const t = await r.text().catch(() => '')
      setResultError(true)
      setResultText(`Export failed${t ? `: ${t}` : ''}`)
      return false
    }
    triggerBlobDownload(await r.blob(), filename)
    return true
  }

  function openFilenameModal() {
    const len = CELLS[order]
    if (!initialPuzzle || initialPuzzle === '.'.repeat(len)) {
      setResultError(true)
      setResultText('Load a puzzle first (not an empty grid).')
      return
    }
    const defaultName = sanitizeDownloadBase(
      (puzzleName || 'puzzle').replace(/\s+/g, '_') + (dlKind === 'progress' ? '_progress' : ''),
      'puzzle',
    )
    setFilenameDraft(defaultName)
    setDownloadOpen(false)
    setFilenameOpen(true)
  }

  return (
    <main className="gm-page">
      <ModePopSquares count={56} seed={3003} />
      <div className="gm-layout">
        <header className="gm-header">
          <h1 className="gm-title">SudoPHASE</h1>
          <p className="gm-subtitle">{puzzleName} · {formatTime(elapsed)}</p>
        </header>

        <aside className="gm-sidebar">
          <button type="button" className="gm-sb-btn" onClick={() => setPickerOpen(true)}>Choose puzzle</button>
          <button type="button" className="gm-sb-btn primary" onClick={solvePuzzle} disabled={solving}>Solve</button>
          <button type="button" className="gm-sb-btn danger" onClick={stopSolve} disabled={!solving}>Stop</button>
          <button type="button" className="gm-sb-btn" onClick={() => setDownloadOpen(true)}>Download</button>

          <div className="gm-sidebar-keypad" aria-label="Keypad">
            <div className="gm-row gm-row--keypad">
              <button type="button" className="gm-icon-btn" onClick={eraseSelected}>Erase</button>
              <button type="button" className="gm-icon-btn" onClick={undo} disabled={!canUndo}>Undo</button>
              <button type="button" className="gm-icon-btn" onClick={clearBoard}>Clear</button>
            </div>

            <div className="gm-digits" style={{ ['--gm-digit-cols']: order }}>
              {Array.from({ length: n }, (_, i) => i + 1).map((d) => (
                <button
                  key={d}
                  type="button"
                  className="gm-digit"
                  onClick={() => setDigit(d)}
                >
                  {d}
                </button>
              ))}
            </div>
          </div>
        </aside>

        <section className="gm-main">
          <section className="gm-grid-wrap">
            <SudokuBoardCanvas
              order={order}
              cellValues={normalizedValues}
              fixedSet={fixedSet}
              conflictSet={conflictSet}
              selectedIndex={selectedIndex}
              onSelectCell={setSelectedIndex}
              onKeyInput={handleBoardKey}
            />
          </section>

          {resultText ? <p className={`gm-result ${resultError ? 'err' : ''}`}>{resultText}</p> : null}
        </section>
      </div>

      <button
        type="button"
        className="gm-help-fab"
        onClick={() => setHelpOpen(true)}
        aria-label="How to play"
        title="How to play"
      >
        ?
      </button>

      <Link to="/" className="gm-home-fab" aria-label="Main menu" title="Main menu">
        ⌂
      </Link>

      <HowToPlayModal open={helpOpen} onClose={() => setHelpOpen(false)} />

      {pickerOpen ? (
        <div className="gm-modal-backdrop" onClick={(e) => { if (e.target === e.currentTarget) setPickerOpen(false) }}>
          <section className="gm-modal">
            <h3>Choose a puzzle</h3>
            <div className="gm-modal-row">
              <label>
                Source
                <select value={pickerSource} onChange={(e) => setPickerSource(e.target.value)}>
                  <option value="curated">Curated dataset</option>
                  <option value="created">Created puzzles</option>
                  <option value="upload">Upload .txt file</option>
                </select>
              </label>
              {pickerSource !== 'upload' ? (
                <label>
                  Size
                  <select value={pickerSize} onChange={(e) => setPickerSize(e.target.value)}>
                    <option value="9×9">9×9</option>
                    <option value="16×16">16×16</option>
                    <option value="25×25">25×25</option>
                  </select>
                </label>
              ) : null}
            </div>
            {pickerSource === 'upload' ? (
              <button type="button" className="gm-btn primary" onClick={() => fileInputRef.current?.click()}>Choose .txt file</button>
            ) : (
              <ul className="gm-list" key={`${pickerSource}-${pickerSize}`}>
                {pickerItems.length ? pickerItems.map((item) => {
                  const value = pickerSource === 'curated' ? item.path : item.id
                  return (
                    <li
                      key={String(value)}
                      className={String(pickerSelected) === String(value) ? 'sel' : ''}
                      onClick={() => setPickerSelected(String(value))}
                    >
                      {item.name}
                    </li>
                  )
                }) : <li className="muted">(no puzzles)</li>}
              </ul>
            )}
            <div className="gm-modal-actions">
              <button type="button" className="gm-btn" onClick={() => setPickerOpen(false)}>Cancel</button>
              <button type="button" className="gm-btn primary" onClick={pickLoad}>Load</button>
            </div>
          </section>
        </div>
      ) : null}

      {downloadOpen ? (
        <div className="gm-modal-backdrop" onClick={(e) => { if (e.target === e.currentTarget) setDownloadOpen(false) }}>
          <section className="gm-modal">
            <h3>Download</h3>
            <fieldset className="gm-fieldset">
              <legend>Content</legend>
              <label><input type="radio" checked={dlKind === 'initial'} onChange={() => setDlKind('initial')} /> Initial puzzle (clues only)</label>
              <label><input type="radio" checked={dlKind === 'progress'} onChange={() => setDlKind('progress')} /> Puzzle Solved/Progress (initial + current grid)</label>
            </fieldset>
            <fieldset className="gm-fieldset">
              <legend>Document type</legend>
              <label><input type="radio" checked={dlFmt === 'txt'} onChange={() => setDlFmt('txt')} /> Text (.txt)</label>
              <label><input type="radio" checked={dlFmt === 'pdf'} onChange={() => setDlFmt('pdf')} /> PDF (.pdf)</label>
            </fieldset>
            <div className="gm-modal-actions">
              <button type="button" className="gm-btn" onClick={() => setDownloadOpen(false)}>Cancel</button>
              <button type="button" className="gm-btn primary" onClick={openFilenameModal}>Download</button>
            </div>
          </section>
        </div>
      ) : null}

      {filenameOpen ? (
        <div className="gm-modal-backdrop" onClick={(e) => { if (e.target === e.currentTarget) setFilenameOpen(false) }}>
          <section className="gm-modal">
            <h3>Document name</h3>
            <label className="gm-input-label">
              File name
              <input
                className="gm-input"
                value={filenameDraft}
                onChange={(e) => setFilenameDraft(e.target.value)}
                placeholder="Enter document name…"
              />
            </label>
            <div className="gm-modal-actions">
              <button type="button" className="gm-btn" onClick={() => { setFilenameOpen(false); setDownloadOpen(true) }}>Go back</button>
              <button
                type="button"
                className="gm-btn primary"
                onClick={async () => {
                  const ok = await runDownload()
                  if (ok) setFilenameOpen(false)
                }}
              >
                Okay
              </button>
            </div>
          </section>
        </div>
      ) : null}

      {solvedOpen ? (
        <div className="gm-modal-backdrop" onClick={(e) => { if (e.target === e.currentTarget) setSolvedOpen(false) }}>
          <section className="gm-modal">
            <h3>Puzzle solved</h3>
            <p className="gm-solved-text">{solvedMsg || 'Puzzle solved.'}</p>
            <div className="gm-modal-actions">
              <button type="button" className="gm-btn primary" onClick={() => setSolvedOpen(false)}>Okay</button>
            </div>
          </section>
        </div>
      ) : null}

      <input
        ref={fileInputRef}
        type="file"
        accept=".txt,text/plain"
        style={{ display: 'none' }}
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = ''
          if (file) onUploadFile(file)
        }}
      />
      <div className="mode-corner-label" aria-hidden>Game Mode</div>
    </main>
  )
}

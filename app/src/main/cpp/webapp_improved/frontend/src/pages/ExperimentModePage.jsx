import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import SudokuBoardCanvas from '../components/SudokuBoardCanvas'
import ModePopSquares from '../components/ModePopSquares'
import HelpIcon from '../components/HelpIcon'
import HowToUsePanel from '../components/HowToUsePanel'
import {
  CELLS,
  SIZES,
  CREATED_KEY,
  computeConflictSet,
  displayValuesToPuzzle,
  getCreatedPuzzlesByOrder,
  mergeBestSolutionDisplay,
  parseInstanceText,
  puzzleToDisplayValues,
  sanitizeDownloadBase,
} from '../lib/sudoku'

function sizeLabelFromOrder(order) {
  return order === 3 ? '9×9' : order === 4 ? '16×16' : '25×25'
}

const defaultParams = {
  // Must match backend defaults in `webapp/app.py` (/api/solve)
  timeout: 180,
  threads: 4,
  ants: 25,
  evap: 0.0075,
  saTinit: 5.75,
  saTmin: 0.01,
  safreq: 25,
  saCooling: 0.995,
  commThreshold: 100,
  commEarly: 60,
  commLate: 25,
  alg: 2,
}

export default function ExperimentModePage() {
  const [order, setOrder] = useState(3)
  const [values, setValues] = useState(Array(81).fill(''))
  const [initialPuzzle, setInitialPuzzle] = useState('.'.repeat(81))
  const [puzzleName, setPuzzleName] = useState('Puzzle')
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [resultText, setResultText] = useState('')
  const [resultError, setResultError] = useState(false)
  const [solvedOpen, setSolvedOpen] = useState(false)
  const [solvedMsg, setSolvedMsg] = useState('')
  const [howToUseOpen, setHowToUseOpen] = useState(false)

  const [library, setLibrary] = useState({})
  const [source, setSource] = useState('curated')
  const [sizeLabel, setSizeLabel] = useState('9×9')
  const [createdListRevision, setCreatedListRevision] = useState(0)
  const [selectedItem, setSelectedItem] = useState('')

  const [solving, setSolving] = useState(false)
  const [activeJobId, setActiveJobId] = useState(null)
  const [lastSolvedParams, setLastSolvedParams] = useState(null)
  const [downloadOpen, setDownloadOpen] = useState(false)
  const [filenameOpen, setFilenameOpen] = useState(false)
  const [filenameDraft, setFilenameDraft] = useState('')
  const [dlKind, setDlKind] = useState('initial')
  const [dlFmt, setDlFmt] = useState('txt')
  const uploadRef = useRef(null)
  const historyRef = useRef([])
  /** Prefetched `/api/instance` payloads so Load can apply without waiting on the network. */
  const curatedInstanceCacheRef = useRef(new Map())
  const [historyTick, setHistoryTick] = useState(0)

  const [params, setParams] = useState(defaultParams)
  const n = order * order
  const normalizedValues = useMemo(() => {
    const len = CELLS[order]
    return Array.from({ length: len }, (_, i) => {
      const v = values?.[i]
      return v == null ? '' : String(v)
    })
  }, [values, order])
  useEffect(() => {
    fetch('/api/library')
      .then((r) => r.json())
      .then((d) => setLibrary(d || {}))
      .catch(() => setLibrary({}))
  }, [])

  const listItems = useMemo(() => {
    const ord = SIZES[sizeLabel] || 3
    if (source === 'curated') return library[sizeLabel] || []
    if (source === 'created') {
      void createdListRevision // invalidate after localStorage delete (same-tab)
      return getCreatedPuzzlesByOrder(ord).map((x) => ({ ...x, path: x.id }))
    }
    return []
  }, [source, sizeLabel, library, createdListRevision])

  useEffect(() => {
    setSelectedItem('')
  }, [source, sizeLabel, library, createdListRevision])

  useEffect(() => {
    if (source !== 'curated' || !selectedItem) return
    if (curatedInstanceCacheRef.current.has(selectedItem)) return
    let cancelled = false
    fetch(`/api/instance/${encodeURIComponent(selectedItem)}`)
      .then((r) => r.json().then((d) => ({ r, d })))
      .then(({ r, d }) => {
        if (cancelled || !r.ok || !d || typeof d.puzzle !== 'string') return
        curatedInstanceCacheRef.current.set(selectedItem, { order: d.order, puzzle: d.puzzle })
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [source, selectedItem])

  useEffect(() => {
    const created = sessionStorage.getItem('createdPuzzle')
    if (!created) return
    try {
      const data = JSON.parse(created)
      sessionStorage.removeItem('createdPuzzle')
      if (data?.order && data?.puzzle) applyLoadedPuzzle(data.order, data.puzzle, data.name || 'Created puzzle')
    } catch {
      // ignore
    }
  }, [])

  const fixedSet = useMemo(() => {
    const s = new Set()
    const str = (initialPuzzle || '').padEnd(CELLS[order], '.')
    for (let i = 0; i < str.length; i += 1) if (str[i] !== '.') s.add(i)
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
    setOrder(nextOrder)
    setSizeLabel(sizeLabelFromOrder(nextOrder))
    setValues(puzzleToDisplayValues(puzzle, nextOrder))
    setInitialPuzzle(puzzle)
    setPuzzleName(name || 'Puzzle')
    setSelectedIndex(0)
    setResultText('')
    setResultError(false)
    setLastSolvedParams(null)
    historyRef.current = []
    setHistoryTick((x) => x + 1)
  }

  function handleCellInput(i, raw) {
    if (fixedSet.has(i)) return
    let v = raw.replace(/\D/g, '')
    if (n <= 9) v = v.slice(0, 1)
    else {
      const num = parseInt(v, 10)
      if (!Number.isNaN(num) && num > n) v = String(n)
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
      handleCellInput(i, '')
      return
    }
    if (n <= 9) {
      if (/^[1-9]$/.test(key)) handleCellInput(i, key)
      return
    }
    const cur = String(normalizedValues[i] || '').replace(/\D/g, '')
    if (!/^\d$/.test(key)) return
    handleCellInput(i, (cur + key).slice(0, 2))
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

  async function loadSelected() {
    if (!selectedItem) return
    try {
      if (source === 'curated') {
        const cached = curatedInstanceCacheRef.current.get(selectedItem)
        if (cached?.order != null && typeof cached.puzzle === 'string') {
          applyLoadedPuzzle(cached.order, cached.puzzle, (selectedItem || '').split('/').pop() || 'Puzzle')
        } else {
          const r = await fetch(`/api/instance/${encodeURIComponent(selectedItem)}`)
          const d = await r.json()
          if (!r.ok) throw new Error(d.error || 'Load failed')
          curatedInstanceCacheRef.current.set(selectedItem, { order: d.order, puzzle: d.puzzle })
          applyLoadedPuzzle(d.order, d.puzzle, (selectedItem || '').split('/').pop() || 'Puzzle')
        }
      } else {
        const list = getCreatedPuzzlesByOrder(SIZES[sizeLabel] || 3)
        const item = list.find((x) => String(x.id) === String(selectedItem))
        if (!item) throw new Error('Created puzzle not found.')
        applyLoadedPuzzle(item.order, item.puzzle, item.name || 'Created puzzle')
      }
    } catch (e) {
      setResultError(true)
      setResultText(e.message || 'Load failed')
    }
  }

  function deleteSelectedCreated() {
    if (source !== 'created' || !selectedItem) return
    const ord = SIZES[sizeLabel] || 3
    const row = getCreatedPuzzlesByOrder(ord).find((x) => String(x.id) === String(selectedItem))
    if (!row) return
    const raw = localStorage.getItem(CREATED_KEY)
    let list = []
    try {
      list = raw ? JSON.parse(raw) : []
    } catch {
      list = []
    }
    if (!Array.isArray(list)) return
    let removed = false
    const next = list.filter((x) => {
      if (removed) return true
      if (
        x &&
        typeof x.puzzle === 'string' &&
        Number(x.order) === Number(row.order) &&
        x.puzzle === row.puzzle &&
        (x.createdAt || 0) === (row.createdAt || 0)
      ) {
        removed = true
        return false
      }
      return true
    })
    localStorage.setItem(CREATED_KEY, JSON.stringify(next))
    setCreatedListRevision((r) => r + 1)
    setSelectedItem('')
  }

  async function solvePuzzle() {
    if (!initialPuzzle.includes('.')) {
      setResultError(true)
      setResultText('Grid is already full')
      return
    }
    if (!(params.evap > 0 && params.evap < 1)) {
      alert('Invalid Evaporation Rate. Must be between 0 and 1.')
      return
    }
    if (!(params.saCooling > 0 && params.saCooling < 1)) {
      alert('Invalid Cooling Rate. Must be between 0 and 1.')
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
        body: JSON.stringify({ puzzle: initialPuzzle, order, ...params }),
      })
      const postData = await postRes.json().catch(() => ({}))
      if (!postRes.ok || !postData.job_id) throw new Error(postData.error || 'Failed to start solver')
      setActiveJobId(postData.job_id)
      const deadline = Date.now() + (params.timeout + 20) * 1000
      while (true) {
        if (Date.now() > deadline) throw new Error('Request timed out')
        const r = await fetch(`/api/status/${encodeURIComponent(postData.job_id)}`)
        const data = await r.json().catch(() => ({}))
        if (!r.ok || data.error) throw new Error(data.error || 'Status error')
        if (data.status === 'done' || data.status === 'error') {
          const res = data.result || {}
          if (res.success && res.solution) {
            setValues(puzzleToDisplayValues(res.solution, order))
            setLastSolvedParams({ ...params })
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
      params: includeParams ? (lastSolvedParams || params) : {},
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

  function onUploadFile(file) {
    const reader = new FileReader()
    reader.onload = () => {
      try {
        const parsed = parseInstanceText(String(reader.result || ''))
        applyLoadedPuzzle(parsed.order, parsed.puzzle, (file.name || 'Uploaded').replace(/\.txt$/i, ''))
      } catch (e) {
        setResultError(true)
        setResultText(e.message || 'Failed to parse file')
      }
    }
    reader.readAsText(file, 'utf-8')
  }

  return (
    <main className="exp-page">
      <ModePopSquares count={56} seed={4004} />
      <aside className="exp-sidebar">
        <h2>Puzzle library</h2>
        <label>Source</label>
        <select value={source} onChange={(e) => setSource(e.target.value)}>
          <option value="curated">Curated dataset</option>
          <option value="created">Created puzzles</option>
          <option value="upload">Upload .txt file</option>
        </select>
        {source !== 'upload' ? (
          <>
            <label>Size</label>
            <select
              value={sizeLabel}
              onChange={(e) => {
                setSizeLabel(e.target.value)
                const newOrder = SIZES[e.target.value] || 3
                setOrder(newOrder)
                setValues(Array(CELLS[newOrder]).fill(''))
              }}
            >
              <option value="9×9">9×9</option>
              <option value="16×16">16×16</option>
              <option value="25×25">25×25</option>
            </select>
            <label>Instances</label>
            <ul className="exp-list" key={`${source}-${sizeLabel}`}>
              {listItems.length ? listItems.map((item) => {
                const key = source === 'curated' ? item.path : item.id
                return (
                  <li
                    key={String(key)}
                    className={String(selectedItem) === String(key) ? 'sel' : ''}
                    onClick={() => setSelectedItem(String(key))}
                  >
                    {item.name}
                  </li>
                )
              }) : <li className="muted">(none)</li>}
            </ul>
            <button type="button" className="exp-btn" onClick={loadSelected}>Load selected</button>
            {source === 'created' ? (
              <button type="button" className="exp-btn" onClick={deleteSelectedCreated}>Delete selected</button>
            ) : null}
          </>
        ) : (
          <button type="button" className="exp-btn" onClick={() => uploadRef.current?.click()}>
            Choose .txt file
          </button>
        )}

        <section className="exp-params">
          <h3>Parameters</h3>
          {[
            ['timeout', 'Timeout (s)'],
            ['ants', 'Number of Ants'],
            ['evap', 'Evap. Rate'],
            ['saTinit', 'Initial Temp'],
            ['saTmin', 'Stopping Temp'],
            ['safreq', 'Frequency'],
            ['saCooling', 'Cooling Rate'],
            ['threads', 'Thread Count'],
            ['commThreshold', 'Comm. Threshold'],
            ['commEarly', 'Early Comm.'],
            ['commLate', 'Late Comm.'],
          ].map(([key, label]) => (
            <label key={key}>
              <span>{label}</span>
              <input
                type="number"
                value={params[key]}
                onChange={(e) => setParams((p) => ({ ...p, [key]: Number(e.target.value) }))}
              />
            </label>
          ))}
          <button type="button" className="exp-btn primary" onClick={solvePuzzle} disabled={solving}>SOLVE PUZZLE</button>
          <button type="button" className="exp-btn danger" onClick={stopSolve} disabled={!solving}>Stop</button>
          <button type="button" className="exp-btn" onClick={() => setDownloadOpen(true)}>Download</button>
        </section>
      </aside>

      <section className="exp-main">
        <div className="exp-center">
          <div className="exp-board">
            <header className="exp-header">
              <h1 className="gm-title">SudoPHASE</h1>
              <p className="gm-subtitle">{puzzleName}</p>
            </header>

            <div className="exp-board-shell">
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

              <HelpIcon
                className="exp-help-icon--board"
                label="How to use Experiment Mode"
                onClick={() => setHowToUseOpen((v) => !v)}
              />

              <Link to="/" className="exp-home-fab" aria-label="Main menu" title="Main menu">
                ⌂
              </Link>

              <HowToUsePanel open={howToUseOpen} onClose={() => setHowToUseOpen(false)} />
            </div>
            {resultText ? <p className={`gm-result ${resultError ? 'err' : ''}`}>{resultText}</p> : null}
          </div>

          <aside className="exp-keypad" aria-label="Keypad">
            <div className="gm-row gm-row--keypad">
              <button type="button" className="gm-icon-btn exp-icon-btn" onClick={eraseSelected}>Erase</button>
              <button type="button" className="gm-icon-btn exp-icon-btn" onClick={undo} disabled={!canUndo}>Undo</button>
              <button type="button" className="gm-icon-btn exp-icon-btn" onClick={clearBoard}>Clear</button>
            </div>
            <div className="gm-digits exp-digits" style={{ ['--gm-digit-cols']: order }}>
              {Array.from({ length: n }, (_, i) => i + 1).map((d) => (
                <button key={d} type="button" className="gm-digit exp-digit" onClick={() => setDigit(d)}>{d}</button>
              ))}
            </div>
          </aside>
        </div>
      </section>

      {downloadOpen ? (
        <div className="gm-modal-backdrop" onClick={(e) => { if (e.target === e.currentTarget) setDownloadOpen(false) }}>
          <section className="gm-modal">
            <h3>Download</h3>
            <fieldset className="gm-fieldset">
              <legend>Content</legend>
              <label><input type="radio" checked={dlKind === 'initial'} onChange={() => setDlKind('initial')} /> Initial puzzle (clues only)</label>
              <label><input type="radio" checked={dlKind === 'progress'} onChange={() => setDlKind('progress')} /> Progress (initial + current grid)</label>
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
        ref={uploadRef}
        type="file"
        accept=".txt,text/plain"
        style={{ display: 'none' }}
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = ''
          if (file) onUploadFile(file)
        }}
      />
      <div className="mode-corner-label" aria-hidden>Experiment Mode</div>
    </main>
  )
}


import { useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import ModePopSquares from '../components/ModePopSquares'
import { parseInstanceText, sanitizeDownloadBase } from '../lib/sudoku'

const FORMAT_TEXT = `Plain text, UTF-8. Lines that start with # are treated as comments and ignored.\n\n<order>       -> 3 = 9×9, 4 = 16×16, 5 = 25×25\n<idum>        -> integer (often 0), ignored by the web app\n<n² rows of n² integers> -> -1 means empty cell\n\nValue encoding:\n- 9×9:  1–9, -1 empty\n- 16×16: 1–10 are 0–9, 11–16 are a–f, -1 empty\n- 25×25: 1–25 map to a–y, -1 empty`

export default function UploadPuzzlePage() {
  const nav = useNavigate()
  const [open, setOpen] = useState(true)
  const [target, setTarget] = useState('game')
  const [file, setFile] = useState(null)
  const fileRef = useRef(null)

  function load() {
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      try {
        const parsed = parseInstanceText(String(reader.result || ''))
        const payload = {
          order: parsed.order,
          puzzle: parsed.puzzle,
          name: sanitizeDownloadBase((file.name || 'Uploaded').replace(/\.txt$/i, ''), 'Uploaded'),
        }
        sessionStorage.setItem('createdPuzzle', JSON.stringify(payload))
        nav(target === 'game' ? '/game' : '/play')
      } catch {
        // ignore
      }
    }
    reader.readAsText(file, 'utf-8')
  }

  return (
    <main className="up-page">
      <ModePopSquares count={56} seed={5007} />
      <Link to="/puzzles" className="puz-return-fab" aria-label="Return to Puzzles" title="Return to Puzzles">
        ↩
      </Link>
      <Link to="/" className="puz-home-fab" aria-label="Home" title="Home">
        ⌂
      </Link>

      <div className="up-col">
        <h1 className="up-title">Upload puzzle</h1>

        <p className="up-hint">
          Load a puzzle from a <code>.txt</code> file (instance format), then open it in Game Mode or Experiment Mode.
        </p>

        <button type="button" className="up-accordion" onClick={() => setOpen((x) => !x)}>
          {open ? '▼' : '▶'} Accepted puzzle file format (.txt)
        </button>
        {open ? <pre className="up-format">{FORMAT_TEXT}</pre> : null}

        <h3 className="up-subtitle">Open in</h3>
        <div className="up-radio">
          <label><input type="radio" checked={target === 'game'} onChange={() => setTarget('game')} /> Game Mode</label>
          <label><input type="radio" checked={target === 'exp'} onChange={() => setTarget('exp')} /> Experiment Mode</label>
        </div>

        <div className="up-row">
          <button type="button" className="up-btn" onClick={() => fileRef.current?.click()}>Choose File</button>
          <div className="up-file">{file ? file.name : 'No file chosen'}</div>
          <button type="button" className="up-btn primary" disabled={!file} onClick={load}>Load puzzle</button>
        </div>
      </div>

      <input
        ref={fileRef}
        type="file"
        accept=".txt,text/plain"
        style={{ display: 'none' }}
        onChange={(e) => {
          const f = e.target.files?.[0] || null
          e.target.value = ''
          setFile(f)
        }}
      />
    </main>
  )
}


import { useCallback, useEffect, useLayoutEffect, useMemo, useRef } from 'react'

/** Must match `.gm-grid-wrap { max-width: min(…px, 100%) }` */
const GRID_SHELL_MAX = 1920

/**
 * Renders the puzzle entirely on a canvas (no CSS grid / table).
 * Selection and edits use callbacks; digit pad can call parent setters as before.
 */
export default function SudokuBoardCanvas({
  order,
  cellValues,
  fixedSet,
  selectedIndex,
  conflictSet,
  onSelectCell,
  onKeyInput,
  className = '',
}) {
  const canvasRef = useRef(null)
  const wrapRef = useRef(null)

  const n = order * order
  const total = n * n
  const peerSet = useMemo(() => {
    if (selectedIndex < 0 || selectedIndex >= total) return new Set()

    const row = Math.floor(selectedIndex / n)
    const col = selectedIndex % n
    const boxRowStart = Math.floor(row / order) * order
    const boxColStart = Math.floor(col / order) * order
    const peers = new Set()

    for (let c = 0; c < n; c += 1) peers.add(row * n + c)
    for (let r = 0; r < n; r += 1) peers.add(r * n + col)
    for (let r = 0; r < order; r += 1) {
      for (let c = 0; c < order; c += 1) {
        peers.add((boxRowStart + r) * n + (boxColStart + c))
      }
    }
    peers.delete(selectedIndex)
    return peers
  }, [selectedIndex, total, n, order])

  const draw = useCallback(() => {
    const canvas = canvasRef.current
    const wrap = wrapRef.current
    if (!canvas || !wrap) return

    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const vp = Math.min(window.innerWidth, window.innerHeight)
    const viewportBudget = Math.max(320, Math.floor(vp * 0.92) - 32)
    // Larger min cells + caps for 9×9 / 16×16 so boards read more comfortably.
    const minCellPx = order === 3 ? 48 : order === 4 ? 34 : 20
    const minSide = n * minCellPx
    const widthBudget = Math.max(320, window.innerWidth - 32)
    const heightBudget = Math.max(320, Math.floor(window.innerHeight * 0.9) - 56)
    const maxSide =
      order === 3
        ? Math.min(1260, widthBudget, heightBudget, viewportBudget)
        : order === 4
          ? Math.min(1536, widthBudget, heightBudget, viewportBudget)
          : Math.min(1520, widthBudget, heightBudget)

    const gridShell = wrap.parentElement
    const columnHost = gridShell?.parentElement
    const shellCs = gridShell ? window.getComputedStyle(gridShell) : null
    const shellPadX =
      shellCs != null
        ? (parseFloat(shellCs.paddingLeft) || 0) + (parseFloat(shellCs.paddingRight) || 0)
        : 0
    let availW = widthBudget
    if (columnHost && columnHost.clientWidth > 0) {
      availW = Math.min(GRID_SHELL_MAX, columnHost.clientWidth) - shellPadX
    }
    availW = Math.max(minSide, availW)

    const rawSize = Math.min(maxSide, availW, heightBudget)
    const cssSize = Math.round(Math.max(minSide, rawSize))
    const cellCss = cssSize / n

    canvas.style.width = `${cssSize}px`
    canvas.style.height = `${cssSize}px`
    canvas.width = Math.round(cssSize * dpr)
    canvas.height = Math.round(cssSize * dpr)

    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cssSize, cssSize)

    /* Match SudoPHASE purple / gold UI (Experiment + Game pages). */
    const bg = '#2a2145'
    const lineThin = 'rgba(172, 154, 227, 0.42)'
    const lineThick = 'rgba(248, 213, 41, 0.78)'
    const textUser = '#f4ecff'
    const textFixed = '#f8d529'
    const peerFill = 'rgba(248, 213, 41, 0.12)'
    const selFill = 'rgba(248, 213, 41, 0.22)'
    const errFill = 'rgba(248, 113, 113, 0.22)'
    const errText = '#ffb4c7'

    ctx.fillStyle = bg
    ctx.fillRect(0, 0, cssSize, cssSize)

    const vals = cellValues.length >= total ? cellValues : [...cellValues, ...Array(total - cellValues.length).fill('')]

    for (let i = 0; i < total; i += 1) {
      const row = Math.floor(i / n)
      const col = i % n
      const x = col * cellCss
      const y = row * cellCss
      if (peerSet.has(i)) {
        ctx.fillStyle = peerFill
        ctx.fillRect(x, y, cellCss, cellCss)
      }
      if (i === selectedIndex) {
        ctx.fillStyle = selFill
        ctx.fillRect(x, y, cellCss, cellCss)
      }
      if (conflictSet?.has?.(i)) {
        ctx.fillStyle = errFill
        ctx.fillRect(x, y, cellCss, cellCss)
      }
    }

    const fontFrac = order === 3 ? 0.58 : order === 4 ? 0.5 : 0.58
    const baseFont = cellCss * fontFrac
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'

    for (let i = 0; i < total; i += 1) {
      const row = Math.floor(i / n)
      const col = i % n
      const x = col * cellCss + cellCss / 2
      const y = row * cellCss + cellCss / 2
      const raw = String(vals[i] ?? '').trim()
      if (!raw) continue
      const mult = raw.length > 1 ? 0.86 : 1
      const fontSize = Math.min(cellCss * 0.62, baseFont * mult)
      ctx.font = `700 ${fontSize}px "Segoe UI", system-ui, sans-serif`
      ctx.fillStyle = conflictSet?.has?.(i) ? errText : (fixedSet.has(i) ? textFixed : textUser)
      ctx.fillText(raw, x, y)
    }

    const thinW = Math.max(1, Math.min(1.35, cellCss * 0.035))
    const blockW = Math.min(4.5, Math.max(2.4, cellCss * 0.14))

    ctx.strokeStyle = lineThin
    ctx.lineWidth = thinW
    for (let k = 0; k <= n; k += 1) {
      const p = k * cellCss
      ctx.beginPath()
      ctx.moveTo(p, 0)
      ctx.lineTo(p, cssSize)
      ctx.stroke()
      ctx.beginPath()
      ctx.moveTo(0, p)
      ctx.lineTo(cssSize, p)
      ctx.stroke()
    }

    ctx.strokeStyle = lineThick
    ctx.lineWidth = blockW
    for (let k = 0; k <= n; k += order) {
      const p = k * cellCss
      ctx.beginPath()
      ctx.moveTo(p, 0)
      ctx.lineTo(p, cssSize)
      ctx.stroke()
      ctx.beginPath()
      ctx.moveTo(0, p)
      ctx.lineTo(cssSize, p)
      ctx.stroke()
    }

    ctx.strokeStyle = lineThick
    ctx.lineWidth = blockW
    ctx.strokeRect(blockW / 2, blockW / 2, cssSize - blockW, cssSize - blockW)
  }, [order, n, total, cellValues, fixedSet, selectedIndex, conflictSet, peerSet])

  useLayoutEffect(() => {
    // Let layout settle before measuring container widths.
    // Double rAF avoids the "grow" effect caused by rapid reflow + ResizeObserver.
    let raf1 = requestAnimationFrame(() => {
      draw()
      const raf2 = requestAnimationFrame(() => draw())
      // store in outer scope for cleanup
      raf1 = raf2
    })
    return () => cancelAnimationFrame(raf1)
  }, [draw])

  useEffect(() => {
    let raf = 0
    const schedule = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => draw())
    }
    const ro = new ResizeObserver(() => schedule())
    const gridShell = wrapRef.current?.parentElement
    const columnHost = gridShell?.parentElement
    if (columnHost) ro.observe(columnHost)
    const onResize = () => schedule()
    window.addEventListener('resize', onResize, { passive: true })
    return () => {
      ro.disconnect()
      window.removeEventListener('resize', onResize)
      cancelAnimationFrame(raf)
    }
  }, [draw])

  function pointerToIndex(clientX, clientY) {
    const canvas = canvasRef.current
    if (!canvas) return null
    const rect = canvas.getBoundingClientRect()
    const x = clientX - rect.left
    const y = clientY - rect.top
    if (x < 0 || y < 0 || x >= rect.width || y >= rect.height) return null
    const col = Math.floor((x / rect.width) * n)
    const row = Math.floor((y / rect.height) * n)
    if (col < 0 || col >= n || row < 0 || row >= n) return null
    return row * n + col
  }

  function onPointerDown(e) {
    const idx = pointerToIndex(e.clientX, e.clientY)
    if (idx != null) onSelectCell(idx)
  }

  function onKeyDown(e) {
    if (onKeyInput == null) return
    if (selectedIndex < 0 || selectedIndex >= total) return
    if (e.key === 'Backspace' || e.key === 'Delete') {
      e.preventDefault()
      onKeyInput(selectedIndex, '')
      return
    }
    if (/^\d$/.test(e.key)) {
      e.preventDefault()
      onKeyInput(selectedIndex, e.key)
    }
  }

  return (
    <div ref={wrapRef} className={`gm-canvas-board-wrap ${className}`.trim()}>
      <canvas
        ref={canvasRef}
        className="gm-canvas-board"
        role="grid"
        aria-label="Sudoku board"
        tabIndex={0}
        onPointerDown={onPointerDown}
        onKeyDown={onKeyDown}
      />
    </div>
  )
}

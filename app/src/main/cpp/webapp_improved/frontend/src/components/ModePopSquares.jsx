import { useMemo } from 'react'

function mulberry32(seed) {
  let t = seed >>> 0
  return function rand() {
    t += 0x6d2b79f5
    let r = Math.imul(t ^ (t >>> 15), 1 | t)
    r ^= r + Math.imul(r ^ (r >>> 7), 61 | r)
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296
  }
}

export default function ModePopSquares({ count = 52, seed = 2026 }) {
  const squares = useMemo(() => {
    const rand = mulberry32(seed)
    return Array.from({ length: count }, () => {
      const x = rand() * 100
      const y = rand() * 100
      const size = 18 + rand() * 52
      const delay = rand() * 7
      const duration = 9 + rand() * 12
      const floatX = -12 - rand() * 24
      const floatY = -12 - rand() * 26
      const tint = rand() < 0.3 ? 'white' : 'yellow'
      return { x, y, size, delay, duration, floatX, floatY, tint }
    })
  }, [count, seed])

  return (
    <div className="mode-pop-squares home-menu__pop-squares" aria-hidden>
      {squares.map((s, idx) => (
        <span
          key={`${seed}-${idx}`}
          data-tint={s.tint}
          style={{
            ['--x']: `${s.x}%`,
            ['--y']: `${s.y}%`,
            ['--sq']: `${s.size}px`,
            ['--delay']: `${s.delay}s`,
            ['--dur']: `${s.duration}s`,
            ['--fx']: `${s.floatX}px`,
            ['--fy']: `${s.floatY}px`,
          }}
        />
      ))}
    </div>
  )
}


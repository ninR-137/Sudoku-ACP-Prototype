import './App.css'
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useMemo } from 'react'
import GameModePage from './pages/GameModePage'
import ExperimentModePage from './pages/ExperimentModePage'
import PuzzlesHubPage from './pages/PuzzlesHubPage'
import CreatePuzzlePage from './pages/CreatePuzzlePage'
import UploadPuzzlePage from './pages/UploadPuzzlePage'
import AboutGamePage from './pages/AboutGamePage'
import landingBgUrl from './assets/landingpage.svg'

function mulberry32(seed) {
  let t = seed >>> 0
  return function rand() {
    t += 0x6d2b79f5
    let r = Math.imul(t ^ (t >>> 15), 1 | t)
    r ^= r + Math.imul(r ^ (r >>> 7), 61 | r)
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296
  }
}

function PopSquares({ count = 26 }) {
  const squares = useMemo(() => {
    const rand = mulberry32(1337)
    return Array.from({ length: count }, () => {
      const x = 2 + rand() * 96
      const y = 4 + rand() * 92
      const size = 22 + rand() * 64
      const delay = rand() * 6
      const duration = 10 + rand() * 10
      const floatX = -10 - rand() * 22
      const floatY = -12 - rand() * 26
      const tint = rand() < 0.28 ? 'white' : 'yellow'

      return { x, y, size, delay, duration, floatX, floatY, tint }
    })
  }, [count])

  return (
    <div className="home-menu__pop-squares" aria-hidden>
      {squares.map((s, idx) => (
        <span
          key={idx}
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

function HomeMenu() {
  return (
    <main className="home-menu">
      <div className="home-menu__stage">
        <div className="home-menu__artboard">
          <div
            className="home-menu__bg-art"
            style={{ backgroundImage: `url(${landingBgUrl})` }}
            aria-hidden
          />
          <PopSquares count={56} />
          <div className="home-menu__vignette" aria-hidden />

          <div className="home-menu__inner">
            <div className="home-menu__slogan-block">
              <h1 className="home-menu__slogan">
                <span className="home-menu__slogan--yellow">Keep the </span>
                <span className="home-menu__slogan--white">Puzzles </span>
                <span className="home-menu__slogan--yellow">Going</span>
              </h1>
              <p className="home-menu__lede">
                Explore Sudoku in two modes <br /> (GAME & EXPERIMENT) with puzzle creation, upload, export and solver tuning in one place.
              </p>
            </div>

            <nav className="home-menu__nav" aria-label="Main menu">
              <Link to="/game" className="home-menu__btn home-menu__btn--yellow">
                GAME MODE
              </Link>
              <Link to="/play" className="home-menu__btn home-menu__btn--white">
                EXPERIMENT MODE
              </Link>
          <Link to="/puzzles" className="home-menu__btn home-menu__btn--yellow">
                PUZZLES
              </Link>
              <Link to="/about" className="home-menu__btn home-menu__btn--white">
                ABOUT GAME
              </Link>
            </nav>

            <p className="home-menu__title">SudoPHASE</p>
          </div>
        </div>
      </div>
    </main>
  )
}

function PlaceholderPage({ title, fallbackHref }) {
  const location = useLocation()
  return (
    <main className="placeholder-page">
      <section className="placeholder-card">
        <h1>{title}</h1>
        <p>
          This page is the React version placeholder. The full UI can be migrated next.
        </p>
        <div className="actions">
          <Link to="/" className="btn">Back to Menu</Link>
          <a href={fallbackHref} className="btn secondary">
            Open current Flask page
          </a>
        </div>
        <code className="route-label">Current route: {location.pathname}</code>
      </section>
    </main>
  )
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomeMenu />} />
      <Route path="/game" element={<GameModePage />} />
      <Route path="/play" element={<ExperimentModePage />} />
      <Route path="/puzzles" element={<PuzzlesHubPage />} />
      <Route path="/create" element={<CreatePuzzlePage />} />
      <Route path="/upload" element={<UploadPuzzlePage />} />
      <Route path="/library" element={<Navigate to="/puzzles" replace />} />
      <Route path="/about" element={<AboutGamePage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App

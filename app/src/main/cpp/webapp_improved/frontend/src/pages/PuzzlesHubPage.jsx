import { Link } from 'react-router-dom'
import ModePopSquares from '../components/ModePopSquares'

export default function PuzzlesHubPage() {
  return (
    <main className="puz-page">
      <ModePopSquares count={56} seed={5005} />
      <Link to="/" className="puz-home-fab" aria-label="Home" title="Home">
        ⌂
      </Link>
      <header className="puz-top">
        {/* Home icon button in top-right */}
      </header>

      <h1 className="puz-title">Puzzles</h1>
      <p className="puz-subtitle">Choose how you want to bring a puzzle into SudoPHASE.</p>

      <div className="puz-actions">
        <Link to="/create" className="puz-card">
          <h2>Create</h2>
          <p>Design a puzzle by placing the fixed clues yourself.</p>
        </Link>
        <Link to="/upload" className="puz-card">
          <h2>Upload</h2>
          <p>Load a puzzle from a <code>.txt</code> instance file and open it in a mode.</p>
        </Link>
      </div>
    </main>
  )
}


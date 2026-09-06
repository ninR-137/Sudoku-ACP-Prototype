import { useState } from 'react'
import { Link } from 'react-router-dom'
import ModePopSquares from '../components/ModePopSquares'
import RuleCard from '../components/RuleCard'
import MiniSudokuBoard from '../components/MiniSudokuBoard'
import aboutGuide1 from '../assets/About Guide 1.svg'
import experimentModeGuide1 from '../assets/Experiment Mode Guide 1.svg'
import experimentModeGuide2 from '../assets/Experiment Mode Guide 2.svg'
import gameModeGuide1 from '../assets/Game Mode Guide 1.svg'
import gameModeGuide2 from '../assets/Game Mode Guide 2.svg'
import menuGuide from '../assets/Menu Guide.svg'
import puzzleGuide1 from '../assets/Puzzle Guide 1.svg'

function makeCells(pairs) {
  const cells = Array.from({ length: 81 }, () => ({ kind: 'empty', value: '' }))
  for (const p of pairs) {
    const idx = p.i
    if (typeof idx !== 'number' || idx < 0 || idx >= 81) continue
    const kind = p.kind === 'clue' || p.kind === 'user' ? p.kind : 'empty'
    const value = p.value == null ? '' : String(p.value)
    cells[idx] = { kind, value, error: Boolean(p.error) }
  }
  return cells
}

const ROW_RULE = makeCells([
  { i: 27 + 0, kind: 'clue', value: 8 },
  { i: 27 + 1, kind: 'user', value: 1 },
  { i: 27 + 2, kind: 'user', value: 2 },
  { i: 27 + 3, kind: 'clue', value: 3 },
  { i: 27 + 4, kind: 'user', value: 4 },
  { i: 27 + 5, kind: 'user', value: 5 },
  { i: 27 + 6, kind: 'user', value: 6 },
  { i: 27 + 7, kind: 'user', value: 6, error: true },
  { i: 27 + 8, kind: 'user', value: 9 },
  { i: 2, kind: 'clue', value: 7 },
  { i: 10, kind: 'clue', value: 9 },
  { i: 20, kind: 'clue', value: 4 },
  { i: 36 + 4, kind: 'clue', value: 7 },
])

const COL_RULE = makeCells([
  { i: 0 * 9 + 4, kind: 'clue', value: 6 },
  { i: 1 * 9 + 4, kind: 'user', value: 1 },
  { i: 2 * 9 + 4, kind: 'user', value: 9 },
  { i: 3 * 9 + 4, kind: 'clue', value: 7 },
  { i: 4 * 9 + 4, kind: 'user', value: 5 },
  { i: 5 * 9 + 4, kind: 'user', value: 2 },
  { i: 6 * 9 + 4, kind: 'clue', value: 8 },
  { i: 7 * 9 + 4, kind: 'user', value: 3 },
  { i: 8 * 9 + 4, kind: 'user', value: 4 },
  { i: 1, kind: 'clue', value: 2 },
  { i: 8, kind: 'clue', value: 1 },
  { i: 7 * 9 + 7, kind: 'clue', value: 9 },
])

const BOX_RULE = makeCells([
  { i: 3 * 9 + 3, kind: 'clue', value: 4 },
  { i: 3 * 9 + 4, kind: 'user', value: 8 },
  { i: 3 * 9 + 5, kind: 'user', value: 1 },
  { i: 4 * 9 + 3, kind: 'user', value: 9 },
  { i: 4 * 9 + 4, kind: 'clue', value: 6 },
  { i: 4 * 9 + 5, kind: 'user', value: 2 },
  { i: 5 * 9 + 3, kind: 'user', value: 7 },
  { i: 5 * 9 + 4, kind: 'user', value: 5 },
  { i: 5 * 9 + 5, kind: 'clue', value: 3 },
  { i: 0, kind: 'clue', value: 5 },
  { i: 80, kind: 'clue', value: 8 },
])

const FIXED_CLUES = makeCells([
  { i: 0, kind: 'clue', value: 9 },
  { i: 4, kind: 'clue', value: 3 },
  { i: 8, kind: 'clue', value: 1 },
  { i: 10, kind: 'clue', value: 6 },
  { i: 20, kind: 'clue', value: 8 },
  { i: 30, kind: 'clue', value: 7 },
  { i: 40, kind: 'clue', value: 5 },
  { i: 60, kind: 'clue', value: 2 },
  { i: 72, kind: 'clue', value: 4 },
  { i: 1, kind: 'user', value: 2 },
  { i: 2, kind: 'user', value: 7 },
  { i: 12, kind: 'user', value: 4 },
  { i: 13, kind: 'user', value: 9 },
  { i: 49, kind: 'user', value: 1 },
  { i: 50, kind: 'user', value: 6 },
])

const USER_MANUAL_GUIDES = [
  { title: 'Menu Guide', src: menuGuide },
  { title: 'Game Mode Guide 1', src: gameModeGuide1 },
  { title: 'Game Mode Guide 2', src: gameModeGuide2 },
  { title: 'Experiment Mode Guide 1', src: experimentModeGuide1 },
  { title: 'Experiment Mode Guide 2', src: experimentModeGuide2 },
  { title: 'Puzzle Guide 1', src: puzzleGuide1 },
  { title: 'About Guide 1', src: aboutGuide1 },
]

const COMMON_QUESTIONS = [
  {
    question: 'How do I create a puzzle?',
    answer: 'Open Puzzles, choose Create Puzzle, select board size, enter a name, fill clue cells, then save/create.',
  },
  {
    question: 'How do I upload a puzzle?',
    answer: 'Go to Puzzles, select Upload Puzzle, choose a supported .txt instance file, then continue to your target mode.',
  },
  {
    question: 'How do I choose created puzzles in Game Mode and Experiment Mode?',
    answer: 'Open the puzzle/library selector and switch to the Created source to load your saved custom puzzles.',
  },
  {
    question: 'How do I stop a running solve?',
    answer: 'Use Stop, then wait for solver status to update before launching another run.',
  },
  {
    question: 'Why is my puzzle not solving?',
    answer: 'The puzzle may be invalid or unsatisfiable, or your timeout might be too low for the current settings.',
  },
  {
    question: 'What does timeout do?',
    answer: 'Timeout limits how long solving can run. Increasing it gives the solver more time to search.',
  },
  {
    question: 'What do Ants and Evaporation affect?',
    answer: 'These tune ACS search behavior; more ants can increase exploration while evaporation changes pheromone decay.',
  },
  {
    question: 'What is the difference between Game Mode and Experiment Mode?',
    answer: 'Game Mode is streamlined for play, while Experiment Mode exposes advanced tuning controls for algorithm behavior.',
  },
  {
    question: 'Where are my created puzzles saved?',
    answer: 'Created puzzles are stored in browser local storage for this app profile.',
  },
  {
    question: 'Why can’t I edit some cells?',
    answer: 'Fixed clue cells are intentionally locked. Only editable cells accept user input.',
  },
  {
    question: 'Why are some numbers highlighted as errors?',
    answer: 'They conflict with Sudoku rules by repeating in a row, column, or 3x3 box.',
  },
  {
    question: 'Can I use uploaded or created puzzles in both modes?',
    answer: 'Yes. Use the puzzle/library selector in either Game Mode or Experiment Mode.',
  },
  {
    question: 'What file format is accepted for upload?',
    answer: 'Upload a supported Sudoku .txt instance format used by the app parser.',
  },
  {
    question: 'What happens if I refresh the page?',
    answer: 'Current session state may reset, but saved created puzzles usually remain in local storage.',
  },
  {
    question: 'How do I download results or reports?',
    answer: 'Use Download after loading or solving. Available export options depend on mode and current solver state.',
  },
]

function HowToPlayPanel() {
  return (
    <div className="about-panel">
      <h2 className="about-h2">How to Play (Sudoku Rules)</h2>
      <p className="about-lede">
        Sudoku is a logic puzzle where each <b>row</b>, <b>column</b>, and <b>box</b> must contain every number
        exactly once.
      </p>

      <div className="gm-help-modal__grid" role="list">
        <RuleCard
          number={1}
          title="Row Rule"
          description="Each row must contain the numbers 1-9 exactly once."
        >
          <MiniSudokuBoard
            label="Row rule example"
            cells={ROW_RULE}
            highlight={{ type: 'row', index: 3 }}
          />
          <div className="htpm-note">
            The highlighted row shows a <span className="htpm-note__warn">duplicate</span> (red) to illustrate a conflict.
          </div>
        </RuleCard>

        <RuleCard
          number={2}
          title="Column Rule"
          description="Each column must contain the numbers 1-9 exactly once."
        >
          <MiniSudokuBoard
            label="Column rule example"
            cells={COL_RULE}
            highlight={{ type: 'col', index: 4 }}
          />
          <div className="htpm-note">The highlighted column demonstrates a valid set (no repeats).</div>
        </RuleCard>

        <RuleCard
          number={3}
          title="Box Rule"
          description="Each 3x3 box must contain the numbers 1-9 exactly once."
        >
          <MiniSudokuBoard
            label="Box rule example"
            cells={BOX_RULE}
            highlight={{ type: 'box', index: 4 }}
          />
          <div className="htpm-note">Focus on the center 3x3 box - each number appears once.</div>
        </RuleCard>

        <RuleCard
          number={4}
          title="Fixed Clues"
          description="Yellow clues are locked. Fill the remaining cells with your own inputs."
        >
          <MiniSudokuBoard
            label="Fixed clues example"
            cells={FIXED_CLUES}
          />
          <div className="htpm-note">
            Clues are disabled; user cells can be selected.
          </div>
        </RuleCard>
      </div>
    </div>
  )
}

function HighlightOverlay({ label, className = '', style = {} }) {
  return (
    <div className={`um-hint ${className}`.trim()} style={style}>
      <span>{label}</span>
    </div>
  )
}

function MiniUIModel({ title, className = '', children }) {
  return (
    <div className={`um-model ${className}`.trim()}>
      {title ? <div className="um-model__title">{title}</div> : null}
      {children}
    </div>
  )
}

function ManualSection({ title, desc, points, children }) {
  return (
    <section className="um-section">
      <header className="um-section__head">
        <h3 className="about-h3">{title}</h3>
        <p className="um-section__desc">{desc}</p>
      </header>
      <div className="um-section__body">
        <div className="um-model-wrap">{children}</div>
        <ul className="um-points">
          {points.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      </div>
    </section>
  )
}

function UserManual() {
  return (
    <div className="um-grid">
      <ManualSection
        title="1. Main Menu Navigation"
        desc="Start from the home menu and choose the mode you need. Each button takes you directly to a focused workflow."
        points={[
          'Click Game Mode for streamlined solving and play.',
          'Click Experiment Mode for advanced tuning controls.',
          'Use Puzzles for Create/Upload entry points.',
          'Open About Game for rules and this manual.',
        ]}
      >
        <MiniUIModel title="Main Menu Model">
          <div className="um-main-menu">
            <button type="button" className="um-menu-btn um-glow">GAME MODE</button>
            <button type="button" className="um-menu-btn">EXPERIMENT MODE</button>
            <button type="button" className="um-menu-btn">PUZZLES</button>
            <button type="button" className="um-menu-btn">ABOUT GAME</button>
            <HighlightOverlay label="Click here to start Game Mode" className="at-top-right" />
            <HighlightOverlay label="Navigation flow starts here" className="at-bottom-left" />
          </div>
        </MiniUIModel>
      </ManualSection>

      <ManualSection
        title="2. Game Mode"
        desc="Game Mode keeps controls simple: pick a puzzle, solve, and interact with the board quickly."
        points={[
          'Choose Puzzle opens curated/created/upload sources.',
          'Solve / Stop manage solver execution.',
          'Download exports puzzle progress or solution report.',
          'Use keypad buttons to enter or erase values.',
        ]}
      >
        <MiniUIModel title="Game Mode Model">
          <div className="um-layout um-layout--game">
            <aside className="um-side">
              <div className="um-side-btn um-glow">Choose Puzzle</div>
              <div className="um-side-btn">Solve</div>
              <div className="um-side-btn">Stop</div>
              <div className="um-side-btn">Download</div>
            </aside>
            <div className="um-board-block">
              <div className="um-action-strip">
                <span className="um-action-pill">Board Area</span>
                <span className="um-action-pill">Cell Select</span>
                <span className="um-action-pill">Conflict Check</span>
              </div>
              <div className="um-keypad">
                {Array.from({ length: 9 }, (_, i) => (
                  <span key={`gk-${i}`}>{i + 1}</span>
                ))}
              </div>
            </div>
            <HighlightOverlay label="Solve / Stop / Download" className="at-top-left" />
            <HighlightOverlay label="Input numbers using this keypad" className="at-bottom-right" />
          </div>
        </MiniUIModel>
      </ManualSection>

      <ManualSection
        title="3. Experiment Mode"
        desc="Experiment Mode provides deeper algorithm control with parameter tuning and puzzle library selection."
        points={[
          'Load puzzles from library or upload source.',
          'Adjust timeout, ants, evaporation, and SA settings.',
          'Run solve to observe behavior under custom parameters.',
          'Use board + keypad for manual edits during testing.',
        ]}
      >
        <MiniUIModel title="Experiment Mode Model">
          <div className="um-layout um-layout--exp">
            <aside className="um-param-panel">
              <div className="um-param-title">Puzzle Library</div>
              <div className="um-param-chip">Curated</div>
              <div className="um-param-chip">Created</div>
              <div className="um-param-title">Parameters</div>
              <div className="um-param-row">Timeout</div>
              <div className="um-param-row">Ants</div>
              <div className="um-param-row">Evap</div>
              <div className="um-param-row">Cooling</div>
            </aside>
            <div className="um-action-strip">
              <span className="um-action-pill">Solver Start</span>
              <span className="um-action-pill">Status Polling</span>
              <span className="um-action-pill">Result Output</span>
              <span className="um-action-pill">Download</span>
            </div>
            <HighlightOverlay label="Adjust parameters here" className="at-mid-left" />
            <HighlightOverlay label="Advanced control actions" className="at-top-right" />
          </div>
        </MiniUIModel>
      </ManualSection>

      <ManualSection
        title="4. Puzzle Management (Create / Upload)"
        desc="Create puzzles manually or upload existing .txt instances to jump into solving modes."
        points={[
          'Create Puzzle lets you design clues directly on the grid.',
          'Upload Puzzle reads supported .txt instance files.',
          'After load/create, route into Game or Experiment mode.',
          'Use clear naming for easier future selection.',
        ]}
      >
        <MiniUIModel title="Puzzle Management Model">
          <div className="um-puzzle-cards">
            <article className="um-mini-card um-glow">
              <h4>Create Puzzle</h4>
              <div className="um-action-strip">
                <span className="um-action-pill">Choose Size</span>
                <span className="um-action-pill">Enter Name</span>
                <span className="um-action-pill">Save / Create</span>
              </div>
              <p>Design your own puzzle</p>
            </article>
            <article className="um-mini-card">
              <h4>Upload Puzzle</h4>
              <div className="um-upload-box">
                <span className="um-file-pill">instance_09.txt</span>
                <button type="button">Choose File</button>
              </div>
              <p>Upload .txt instance</p>
            </article>
          </div>
        </MiniUIModel>
      </ManualSection>

      <ManualSection
        title="5. Board Interaction & Controls"
        desc="Select a cell, enter numbers, and distinguish fixed clues from editable cells for accurate play."
        points={[
          'Click a cell to focus it before entry.',
          'Fixed clues remain locked and highlighted.',
          'Editable cells accept keypad input.',
          'Use erase/undo/clear for correction flow.',
        ]}
      >
        <MiniUIModel title="Board Interaction Model">
          <div className="um-board-ops">
            <div className="um-action-strip">
              <span className="um-action-pill">Click Cell</span>
              <span className="um-action-pill">Enter Number</span>
              <span className="um-action-pill">Erase</span>
              <span className="um-action-pill">Undo</span>
              <span className="um-action-pill">Clear</span>
            </div>
            <div className="um-keypad um-keypad--ops">
              {Array.from({ length: 9 }, (_, i) => (
                <span key={`bk-${i}`} className={i === 4 ? 'pulse' : ''}>{i + 1}</span>
              ))}
            </div>
            <HighlightOverlay label="Click cell first" className="at-top-left" />
            <HighlightOverlay label="Fixed clue (locked)" className="at-mid-right" />
            <HighlightOverlay label="Number appears here" className="at-bottom-left" />
          </div>
        </MiniUIModel>
      </ManualSection>
    </div>
  )
}

function UserManualPanel() {
  return (
    <div className="about-panel">
      <h2 className="about-h2">User Manual</h2>
      <div className="um-svg-guides">
        {USER_MANUAL_GUIDES.map((guide) => (
          <figure key={guide.title} className="um-svg-guide">
            <figcaption className="um-svg-guide__title">{guide.title}</figcaption>
            <img className="um-svg-guide__image" src={guide.src} alt={guide.title} loading="lazy" />
          </figure>
        ))}
      </div>
    </div>
  )
}

function CommonQuestionsPanel() {
  return (
    <div className="about-panel">
      <h2 className="about-h2">Common Questions</h2>
      <div className="um-notes">
        {COMMON_QUESTIONS.map((item, index) => (
          <section key={item.question} className="um-note-card">
            <h3 className="about-h3">{index + 1}. {item.question}</h3>
            <p className="about-text">{item.answer}</p>
          </section>
        ))}
      </div>
    </div>
  )
}

function AboutFlashCard({ title, icon, children }) {
  const [flipped, setFlipped] = useState(false)
  const toggle = () => setFlipped((v) => !v)

  return (
    <div
      className={`about-flashcard ${flipped ? 'is-flipped' : ''}`}
      onClick={toggle}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          toggle()
        }
      }}
      role="button"
      tabIndex={0}
      aria-pressed={flipped}
      aria-label={`${title} flashcard`}
    >
      <div className="about-flashcard__inner">
        <div className="about-flashcard__face about-flashcard__face--front">
          <h3 className="about-h3">{title}</h3>
          <div className="about-flashcard__icon" aria-hidden>{icon}</div>
          <span className="about-flashcard__hint">Click to reveal</span>
        </div>
        <div className="about-flashcard__face about-flashcard__face--back">
          <h3 className="about-h3">{title}</h3>
          <div className="about-flashcard__content">{children}</div>
        </div>
      </div>
    </div>
  )
}

function AboutPanel() {
  return (
    <div className="about-panel">
      <div className="about-info-cards">
        <AboutFlashCard title="About Game" icon="🎮">
          <p className="about-text">
            SudoPHASE is a research-driven Sudoku web application designed for both gameplay and algorithm experimentation.
          </p>
        </AboutFlashCard>

        <AboutFlashCard title="Purpose" icon="🎯">
          <p className="about-text">
            To provide an interactive platform for solving Sudoku while allowing users to explore and analyze optimization
            algorithms in real time.
          </p>
        </AboutFlashCard>

        <AboutFlashCard title="Key Features" icon="🧩">
          <ul className="about-ul">
            <li>Interactive Sudoku solving (Game Mode)</li>
            <li>Advanced parameter tuning (Experiment Mode)</li>
            <li>Multithreaded solver execution</li>
            <li>Puzzle creation and upload support</li>
            <li>Real-time solver control (start/stop/download)</li>
          </ul>
        </AboutFlashCard>

        <AboutFlashCard title="Author and Developer" icon="👤">
          <p className="about-text">
            Rico Mendez
          </p>
          <p className="about-text">
            GitHub: <a href="https://github.com/grootiesss" target="_blank" rel="noreferrer">https://github.com/grootiesss</a>
          </p>
        </AboutFlashCard>
      </div>
    </div>
  )
}

export default function AboutGamePage() {
  const [tab, setTab] = useState('howto')

  return (
    <main className="about-page">
      <ModePopSquares count={56} seed={6006} />
      <Link to="/" className="puz-home-fab" aria-label="Home" title="Home">
        ⌂
      </Link>
      <section className="about-shell menu-shell">
        <h1 className="about-title">About Game</h1>
        <p className="about-top-desc">
          SudoPHASE is a Sudoku web app powered by Ant Colony System, Simulated Annealing, and multithreaded solving. Use these tabs for Sudoku rules, app guidance, and project background.
        </p>

        <div className="about-tabs" role="tablist" aria-label="About Game tabs">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'howto'}
            className={`about-tab ${tab === 'howto' ? 'active' : ''}`}
            onClick={() => setTab('howto')}
          >
            How to Play
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'manual'}
            className={`about-tab ${tab === 'manual' ? 'active' : ''}`}
            onClick={() => setTab('manual')}
          >
            User Manual
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'common'}
            className={`about-tab ${tab === 'common' ? 'active' : ''}`}
            onClick={() => setTab('common')}
          >
            Common Questions
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'about'}
            className={`about-tab ${tab === 'about' ? 'active' : ''}`}
            onClick={() => setTab('about')}
          >
            About
          </button>
        </div>

        <div className="about-scroll" role="region">
          {tab === 'howto' ? <HowToPlayPanel /> : null}
          {tab === 'manual' ? <UserManualPanel /> : null}
          {tab === 'common' ? <CommonQuestionsPanel /> : null}
          {tab === 'about' ? <AboutPanel /> : null}
        </div>
      </section>
    </main>
  )
}


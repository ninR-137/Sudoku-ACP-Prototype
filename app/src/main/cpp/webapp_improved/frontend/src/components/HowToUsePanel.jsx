import { useEffect } from 'react'

function Section({ title, icon, children }) {
  return (
    <section className="htu-sec">
      <h4 className="htu-sec__title">
        <span className="htu-sec__icon" aria-hidden>
          {icon}
        </span>
        {title}
      </h4>
      <div className="htu-sec__body">{children}</div>
    </section>
  )
}

export default function HowToUsePanel({ open, onClose }) {
  useEffect(() => {
    if (!open) return
    const onKey = (e) => {
      if (e.key === 'Escape') onClose?.()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  return (
    <aside className={`htu ${open ? 'open' : ''}`} aria-hidden={!open}>
      <div className="htu__top">
        <div className="htu__heading">
          <h3>How to Use</h3>
          <p className="htu__sub">A quick guide to Experiment Mode controls and parameters.</p>
        </div>
      </div>

      <div className="htu__scroll">
        <Section title="Puzzle Library (left panel)" icon="📚">
          <ul>
            <li>Choose a <b>source</b> (curated dataset / created puzzles / upload).</li>
            <li>Select the puzzle <b>size</b> (9×9, 16×16, 25×25).</li>
            <li>Pick an instance from the list, then click <b>Load selected</b>.</li>
          </ul>
        </Section>

        <Section title="Parameters" icon="⚙️">
          <ul>
            <li><b>Timeout</b>: how long the solver is allowed to run.</li>
            <li><b>Number of ants</b>: more ants explores more candidates (often slower).</li>
            <li><b>Evaporation rate</b>: how quickly the solver “forgets” older paths.</li>
            <li><b>Temperature</b>: controls how exploratory the search is.</li>
            <li><b>Thread count</b>: how many CPU workers to use.</li>
          </ul>
        </Section>

        <Section title="Controls" icon="▶">
          <ul>
            <li><b>Solve</b>: runs the solver with your current parameters.</li>
            <li><b>Stop</b>: stops the active solve job.</li>
            <li><b>Download</b>: exports results as text/PDF.</li>
          </ul>
        </Section>

        <Section title="Board interaction" icon="🧩">
          <ul>
            <li>Click cells to select them.</li>
            <li>Enter numbers using the keypad (or keyboard).</li>
            <li><b>Fixed clues</b> (yellow) cannot be edited.</li>
          </ul>
        </Section>

        <Section title="Keypad" icon="⌨">
          <ul>
            <li>Use it for manual input while experimenting.</li>
            <li>It works alongside the solver and doesn’t reset your puzzle.</li>
          </ul>
        </Section>
      </div>
    </aside>
  )
}


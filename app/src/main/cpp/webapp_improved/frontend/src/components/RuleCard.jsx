export default function RuleCard({ number, title, description, children }) {
  return (
    <article className="htpm-card">
      <header className="htpm-card__header">
        <div className="htpm-card__badge" aria-hidden>
          {number}
        </div>
        <div className="htpm-card__headings">
          <div className="htpm-card__title">{title}</div>
          <div className="htpm-card__desc">{description}</div>
        </div>
      </header>
      <div className="htpm-card__body">{children}</div>
    </article>
  )
}


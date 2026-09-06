# webapp_improved

React-based frontend workspace for improving the Sudoku web UI while keeping the current Flask backend APIs.

## Structure

- `frontend/` - Vite + React app

## Quick start

1. Start the existing Flask backend (current `webapp/app.py`) on `http://127.0.0.1:5000`.
2. Start React dev server:

   ```bash
   cd webapp_improved/frontend
   npm install
   npm run dev
   ```

3. Open the Vite URL (default `http://127.0.0.1:5174`).

## Notes

- `vite.config.js` includes proxy rules so frontend calls to `/api/*` are forwarded to Flask.
- This is a clean migration workspace; the existing `webapp/` remains unchanged.

## Suggested next migration steps

1. Add React Router pages for:
   - `/`
   - `/game`
   - `/play`
   - `/create`
   - `/upload`
   - `/about`
2. Build shared components:
   - Sudoku grid
   - Number pad
   - Puzzle picker modal
   - Download modal
3. Move API calls to a small `src/api/` layer.

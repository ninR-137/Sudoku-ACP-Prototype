# Reading 9×9, 16×16 and 25×25 Sudoku grids with the existing digit model

> **Handoff note.** This document was written in the model-training repo (`Digit-Recognition-Tensorflow-Model`) for the Sudoku Solver app (`Sudoku-ACP-Prototype`). It is self-contained: everything needed to support 16×16 and 25×25 puzzles is here. The reference code is Python + OpenCV. Port it to the app's language and platform; OpenCV, or an equivalent image library, exists for Android, iOS and desktop.

## Goal

Read puzzles of size **N = 9, 16 or 25**, where each filled cell contains a **number from 1 to N** (so 1–25 at most, never letters).

**Do not train a new 25-class model.** Keep the existing single-digit model and add one step in front of it: split each cell into individual digits, read each digit, then combine them ("1" + "6" → 16). This needs no new dataset and reuses a model that is already about 99% accurate per digit.

A model that reads the whole cell as one of 25 classes was considered and rejected:
- Two digits squashed into 28×28 lose detail.
- The model would have to learn digit order ("12" vs "21", "1" vs "11").
- No dataset with labelled 10–25 cells exists.

---

## 1. The model (unchanged)

| | |
|---|---|
| File currently deployed | `tflite_model_Translation0.05_Rotation0.05_Zoom0.05_Patience8_LR0.001.tflite` |
| SHA-1 | `3f71e8de3aa4d2637f5bd08642142578f3c58c2c` |
| Size | ~430 KB (dynamic-range quantized; weights int8, input/output float32) |
| Input | `float32[1, 28, 28, 1]`, values in `[0, 1]`, **white digit on a black background** |
| Output | `float32[1, 10]`, softmax probabilities for digits 0–9 |
| Accuracy | 98.87% on clean test digits; 98.56% on mixed rotation/shift/zoom; weakest classes are **1** and **7** |
| Trained on | MNIST (handwritten) + TMNIST (printed digits in 2,990 Google Fonts) |

The model expects **one digit, formatted like MNIST**: the longest side scaled to 20 px with the aspect ratio kept, centred by center of mass in a 28×28 canvas. If the crops don't match this format, accuracy drops sharply (§4).

Optional upgrade: the seed-43 model from the same training run (`..._Seed43.tflite`) is slightly better (99.08% clean, digit-1 recall 98.5% vs. 98.0%). It is a drop-in replacement with the same input and output.

---

## 2. Pipeline

```
photo
  → find the outer grid, warp it to a square (perspective transform)
  → detect grid size N ∈ {9, 16, 25}                        §3
  → cut into N×N cells
  → for each cell:
      trim the border (removes grid-line remnants)
      blank?                         → EMPTY                  §5
      binarize, find connected components                     §5
      drop specks, merge broken pieces of the same digit
      0 pieces          → EMPTY
      1 piece, normal width → one digit
      1 piece, too wide     → split at the thinnest column (touching digits)
      2 pieces              → left digit, right digit
      3+ pieces             → keep the 2 largest, mark the cell as low confidence
      each digit → MNIST format (§4) → model → 10 probabilities
      decode with the 1..N rules (§6) → value, confidence, top-3
  → solver: use the confident cells; low-confidence cells keep their top-3 candidates (§7)
```

---

## 3. Detecting the grid size

After warping the grid to a square, count the cells per side:
- Find the grid lines with Hough lines, or with projection profiles (sum the dark pixels along each row and each column; the lines show up as peaks).
- Count the evenly spaced peaks. There are N+1 lines per side.
- Snap the result to the nearest of {9, 16, 25}. Also check the thick box lines: every 3 cells for 9×9, every 4 for 16×16, every 5 for 25×25.

N is used to:
1. split the grid into cells,
2. reject values above N (§6),
3. configure the solver.

Allowing the user to override the detected size in the UI is a cheap safety net.

---

## 4. Formatting a digit like MNIST (most important step)

```python
import cv2
import numpy as np
from scipy import ndimage

def to_mnist(ink):
    """ink: tight crop of ONE digit, uint8, white digit (high) on black (0).
    Returns float32[1, 28, 28, 1] in [0, 1]."""
    h, w = ink.shape
    s = 20.0 / max(h, w)                                  # longest side → 20 px
    nw, nh = max(1, round(w * s)), max(1, round(h * s))   # KEEP the aspect ratio
    d = cv2.resize(ink, (nw, nh), interpolation=cv2.INTER_AREA)
    canvas = np.zeros((28, 28), np.float32)
    y, x = (28 - nh) // 2, (28 - nw) // 2
    canvas[y:y + nh, x:x + nw] = d
    if canvas.sum() > 0:                                  # MNIST centers by center of mass
        cy, cx = ndimage.center_of_mass(canvas)
        canvas = ndimage.shift(canvas, (14 - cy, 14 - cx), order=1, cval=0.0)
    canvas = np.clip(canvas, 0, 255) / 255.0
    return canvas[None, :, :, None].astype(np.float32)
```

Rules:
- **Never stretch a crop to fill 28×28.** A stretched "1" becomes a wide block and gets misread.
- **The background must be black and the digit white.** Invert printed puzzles (dark ink on white paper).
- **Use grayscale ink, not a hard 0/255 mask, where possible.** MNIST digits have soft anti-aliased edges. `ink = 255 - gray`, with pixels outside the digit's mask set to 0, is a good choice.

---

## 5. Splitting a cell into digits

```python
def split_cell(cell_gray, border_frac=0.08, blank_std=12, wide_ratio=0.75):
    """cell_gray: uint8 grayscale cell, dark ink on light paper.
    Returns (options, uncertain). Each option is one possible reading of the
    cell as a list of 1 or 2 ink crops (white on black), left to right.
    There is usually one option; a single wide blob gives two (one digit, or
    two touching digits) and read_cell lets the model decide. options == []
    means the cell is empty."""
    h, w = cell_gray.shape
    m = int(border_frac * min(h, w))
    g = cell_gray[m:h - m, m:w - m]                       # trim grid-line remnants
    if g.std() < blank_std:                               # nearly uniform → blank
        return [], False

    blur = cv2.GaussianBlur(g, (3, 3), 0)
    _, bw = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    ch, cw = bw.shape
    if (bw > 0).mean() < 0.02:                            # almost no ink → blank
        return [], False

    num, labels, stats, _ = cv2.connectedComponentsWithStats(bw, connectivity=8)
    boxes = []
    for i in range(1, num):
        x, y, bwid, bht, area = stats[i]
        if area < 0.01 * ch * cw:                         # specks
            continue
        touches = x == 0 or y == 0 or x + bwid == cw or y + bht == ch
        if touches and (bht > 0.9 * ch or bwid > 0.9 * cw):  # leftover grid line
            continue
        boxes.append([x, y, x + bwid, y + bht])

    # Merge pieces that overlap horizontally (e.g. a "5" drawn in two strokes)
    boxes.sort(key=lambda b: b[0])
    merged = []
    for b in boxes:
        if merged:
            p = merged[-1]
            overlap = min(p[2], b[2]) - max(p[0], b[0])
            if overlap > 0.5 * min(p[2] - p[0], b[2] - b[0]):
                merged[-1] = [min(p[0], b[0]), min(p[1], b[1]), max(p[2], b[2]), max(p[3], b[3])]
                continue
        merged.append(b)

    # Real digits are tall: drop short leftovers (dashes, dots)
    merged = [b for b in merged if (b[3] - b[1]) >= 0.3 * ch]

    uncertain = False
    if len(merged) > 2:                                   # keep the two largest, in reading order
        merged = sorted(merged, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]), reverse=True)[:2]
        merged.sort(key=lambda b: b[0])
        uncertain = True

    if not merged:
        return [], False

    ink = 255 - g
    def crops_for(boxes):
        out = []
        for x1, y1, x2, y2 in boxes:
            sub = bw[y1:y2, x1:x2]
            ys, xs = np.nonzero(sub)                      # re-tighten after a split
            y1, y2, x1, x2 = y1 + ys.min(), y1 + ys.max() + 1, x1 + xs.min(), x1 + xs.max() + 1
            mask = bw[y1:y2, x1:x2] > 0
            out.append(np.where(mask, ink[y1:y2, x1:x2], 0).astype(np.uint8))
        return out

    options = [crops_for(merged)]

    # A single wide blob may be ONE wide digit ("0", "2", "8") or TWO touching
    # digits ("11", "22"). Width alone cannot tell them apart, so offer both
    # readings and let read_cell pick the one the model is more confident in.
    if len(merged) == 1:
        x1, y1, x2, y2 = merged[0]
        if (x2 - x1) / (y2 - y1) > wide_ratio:
            cols = (bw[y1:y2, x1:x2] > 0).sum(axis=0)
            lo, hi = int(0.3 * len(cols)), int(0.7 * len(cols))
            cut = x1 + lo + int(np.argmin(cols[lo:hi]))
            options.append(crops_for([[x1, y1, cut, y2], [cut, y1, x2, y2]]))
    return options, uncertain
```

The default thresholds (`border_frac`, `blank_std`, `wide_ratio`, the 1%, 2% and 30% limits) are starting values. Tune them on the synthetic test set (§8) and on real photos.
- A single "0" or "4" in a wide font can have a width-to-height ratio near 0.8. Keep `wide_ratio` at 0.9 or above, and rely on the decoding rules (§6) to catch mistakes.
- On very small cells, a closing operation (`cv2.morphologyEx`, `MORPH_CLOSE`, 2×2 kernel) before finding components reduces broken strokes.

---

## 6. Decoding with the 1..N rules

Only these values are valid:
- **One digit:** 1–9 (a lone "0" is never valid).
- **Two digits:** 10 up to N. So the first digit is always **1 or 2**, and for N = 25 a first digit of 2 means the second digit is 0–5.

Score every valid number from both digits' probabilities and keep the best one. Don't take each digit's top guess separately. This fixes many errors automatically: if "23" has its "3" read as "8", the next most likely valid number wins.

```python
def decode(probs, n):
    """probs: list of 1 or 2 arrays of 10 probabilities (left to right).
    n: grid size (9, 16 or 25). Returns (value, confidence, top3) or None."""
    if len(probs) == 1:
        cands = range(1, min(9, n) + 1)
        score = {v: float(probs[0][v]) for v in cands}
    elif len(probs) == 2 and n >= 10:
        cands = range(10, n + 1)
        score = {v: float(probs[0][v // 10] * probs[1][v % 10]) for v in cands}
    else:
        return None                                      # e.g. two digits in a 9×9 grid → flag it
    ranked = sorted(score, key=score.get, reverse=True)
    total = sum(score.values()) or 1.0
    best = ranked[0]
    # per-digit strength (geometric mean), comparable between 1- and 2-digit readings
    strength = score[best] ** (1.0 / len(probs))
    return best, score[best] / total, ranked[:3], strength


def read_cell(cell_gray, interpreter, n, min_conf=0.80, margin=0.15):
    options, uncertain = split_cell(cell_gray)
    if not options:
        return {"value": None}                           # empty cell
    inp = interpreter.get_input_details()[0]["index"]
    out = interpreter.get_output_details()[0]["index"]

    def predict(crop):
        interpreter.set_tensor(inp, to_mnist(crop))
        interpreter.invoke()
        return interpreter.get_tensor(out)[0]

    readings = []
    for crops in options:                                # 1 option, or 2 for a wide blob
        res = decode([predict(c) for c in crops], n)
        if res is not None:
            readings.append(res)
    if not readings:
        return {"value": None, "needs_review": True}

    readings.sort(key=lambda r: r[3], reverse=True)      # most confident reading first
    value, conf, top3, strength = readings[0]
    if len(readings) > 1:                                # keep the runner-up as a candidate
        top3 = (top3 + [v for v in readings[1][2] if v not in top3])[:3]
        uncertain = uncertain or strength - readings[1][3] < margin
    return {"value": value, "confidence": conf, "top3": top3,
            "needs_review": uncertain or conf < min_conf}
```

`confidence` is normalized over the valid candidates only. Use it to rank cells against each other; it is not a calibrated probability. Tune `min_conf` on test data.

---

## 7. Integration with the solver

- Put cells with `needs_review = False` into the grid as givens.
- For low-confidence cells, either:
  - **Let the solver choose.** Try the top-3 candidates; the Sudoku constraints (row, column, box) usually leave only one consistent choice. Any candidate that duplicates a confident given in the same row, column or box can be removed straight away.
  - **Ask the user.** Highlight the cell and offer the top-3 as quick choices.
- If the puzzle has no solution with the givens as read, the most likely cause is a misread cell. Retry with the least confident given replaced by its next candidate before reporting an error.

---

## 8. Testing before using real photos

Build a synthetic test set with the same kinds of data the model was trained on:

1. **Two-digit cells.** Put pairs of MNIST/TMNIST test digits side by side to form 10–25. Vary the gap (including touching and slightly overlapping), font size, stroke thickness, blur, noise and partial grid lines at the edges.
2. **Whole grids.** Render 16×16 and 25×25 puzzles in several fonts, at realistic resolutions (cells of 20–60 px), with a small perspective warp, then run the full pipeline.
3. **Report:**
   - **cell accuracy:** the whole number read correctly, measured separately for 1–9 and 10–25;
   - **blank accuracy:** empty cells correctly reported as empty;
   - **grid accuracy:** every cell correct;
   - **review rate:** the share of cells flagged `needs_review`.

### Baseline results of the reference code

The code in §4–§6 was run, unchanged, against the deployed `.tflite`. The test cells were synthetic 56×56 px cells for N = 25: digits at half the cell height, a gap of 1–4 px between digits, 2-px grid-line remnants on two edges and light pixel noise. There were 40 cells per value, and the 100 blank cells used the same noise.

| Test digits | 1–9 | 10–25 | 10–25, digits touching (gap 0) | Blank cells read as empty | Review rate |
|---|---|---|---|---|---|
| Handwritten (MNIST) | 99.2% | 98.3% | 85.0% | 100% | 5.7% |
| Printed (TMNIST, same font in both digits) | 98.1% | 96.6% | 86.4% | 100% | 6.8% |

What this shows:
- Separated digits already work with no retraining.
- **Touching digits are the weakest case.** Improve the cut-point search, or fine-tune on split crops, if real puzzles use tightly spaced bold fonts.
- **A lesson from the first test run:** an early version split every wide blob into two digits, so wide single digits ("2", "5", "8") were read as 12, 15, 18 (single-digit accuracy 75.6%). Trying both readings and letting the model decide (§6) fixed this.
- **Lighting.** Otsu uses one global threshold per cell. Under uneven lighting, a faint digit can vanish next to a dark one. If real photos show this, use `cv2.adaptiveThreshold`, or normalize the illumination of the warped grid first.

Suggested targets before shipping: cell accuracy ≥ 99% on synthetic data, a review rate under 3%, and no confident misreads that the solver can't catch.

If the split crops turn out to look different from MNIST (e.g. a fragment of the neighbouring digit at the edge), fine-tune the **existing 10-digit model** on crops produced by `split_cell` from synthetic two-digit cells. Even then, never switch to a 25-class model.

---

## 9. Known risks

| Risk | Mitigation |
|---|---|
| **Digit "1".** It is the first digit of 10–19 and the model's weakest class (98% recall). | The §6 rules (the first digit can only be 1 or 2); consider the seed-43 model. |
| **Touching digits** in bold fonts ("11", "22") | Width-to-height check + split at the thinnest column (§5) |
| **Broken digits** (a "5" or "4" in separate strokes) | Merge horizontally overlapping pieces (§5) |
| **Small cells in 25×25 grids** (under ~40 px per cell, ~15 px per digit) | Ask for a closer photo or a higher-resolution capture; warp the grid before cutting cells; consider a minimum-resolution check that warns the user. |
| **Grid-line remnants** read as "1" | Trim cell borders; drop components that touch the border and span almost the whole cell (§5) |
| **Wrong grid size detected** | Snap to {9, 16, 25}; check the thick box lines; let the user override |

---

## 10. Implementation checklist

- [ ] Grid-size detection (9 / 16 / 25), with a user override
- [ ] Cell cutting for any N
- [ ] `split_cell`: blank check, components, merge, wide blob → both readings
- [ ] `to_mnist` formatting that keeps the aspect ratio and centres by center of mass
- [ ] `decode` with the 1..N rules and top-3 candidates
- [ ] Solver uses confidences: tries candidates, then asks the user
- [ ] Synthetic test set + accuracy report (§8)
- [ ] Tune thresholds on synthetic data, then on real photos of 16×16 and 25×25 puzzles

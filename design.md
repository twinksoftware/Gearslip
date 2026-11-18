Nothing OS — DESIGN.md
Design System inspired by Nothing OS Dot-matrix retro-futurism. Pure black canvas, NDot typography, surgical white-on-black contrast. A curated starting point for building Nothing OS–style UIs with your AI coding agent.

1. Brand Identity
Nothing OS is built around a single obsession: radical reduction. Every pixel either earns its place or gets cut. The aesthetic is raw, industrial, and unapologetically digital — drawing from IBM mainframe terminals of the 1980s, punk zine layouts, and transparent hardware design. There is no decoration for decoration's sake. The UI feels like it was built by engineers who also happen to be obsessive minimalists.

Core principles:

Transparency over opacity — show the system, not a veneer over it
Monochrome first — colour is a signal, not decoration
Dot matrix as DNA — the NDot typeface and dot-grid iconography pervade every surface
Dark by default — pure black backgrounds, not dark grey
Function is form — layout choices are always ergonomic, never ornamental
2. Color Palette
Nothing OS operates in an almost achromatic world. The only true accent is Nothing Red, used sparingly as an action signal or alert. Everything else lives in a tight band from pure black to pure white.

Primary Palette
Token | Hex | Usage -- | -- | -- color-bg | #000000 | Page / app background (pure black) color-surface-1 | #0D0D0D | Card backgrounds, bottom sheets color-surface-2 | #1A1A1A | Elevated surfaces, modal overlays color-surface-3 | #242424 | Hover states, selected rows color-border | #2E2E2E | Dividers, outlines, input borders color-border-subtle | #1C1C1C | Very subtle separators color-text-primary | #FFFFFF | Headings, primary body copy color-text-secondary | #999999 | Captions, metadata, secondary labels color-text-muted | #555555 | Disabled text, placeholder copy color-accent | #FF3030 | CTAs, error states, Glyph-on indicators color-accent-dim | #3A0A0A | Accent tinted surfaces (e.g. alert cards) color-ghost | rgba(255,255,255,0.06) | Ghost surfaces, subtle overlays
9. Do's and Don'ts
✅ Do
Use #000000 pure black for backgrounds — never #111 or #0A0A0A as a primary canvas
Apply NDot for display text at ≥ 24px only
Keep borders 1px solid — never thicker, never rounded on cards
Use opacity transitions for hover states — never background-color flashes
Let product imagery sit on pure black — crop tightly, no drop shadows
Space navigation labels at 0.06–0.10em letter-spacing
Use the dot-matrix background texture sparingly — one zone per page maximum
Always use spaces inside product name parentheses: Phone ( 3 ), not Phone(3)
❌ Don't
Don't use gradients on backgrounds — Nothing OS has none
Don't add drop shadows to cards or modals — borders communicate elevation
Don't mix NDot with a humanist or serif typeface — it breaks the industrial register
Don't use more than two colours on a single surface (black/white + one accent max)
Don't animate with spring physics or bounce easing
Don't use circular or rounded icon libraries (Material Icons, Heroicons) — dot-grid only
Don't use colour fills for informational states — use border + text colour changes instead
Don't centre display headings — left-align all NDot text
Don't use emoji in UI copy or headings — ever
10. Tailwind CSS Utility Mapping
For projects using Tailwind CSS, extend the config to match Nothing OS tokens:

// tailwind.config.js
module.exports = {
  theme: {
    extend: {
      colors: {
        'nothing': {
          black:     '#000000',
          'surface-1': '#0D0D0D',
          'surface-2': '#1A1A1A',
          'surface-3': '#242424',
          border:    '#2E2E2E',
          white:     '#FFFFFF',
          secondary: '#999999',
          muted:     '#555555',
          red:       '#FF3030',
          'red-dim': '#3A0A0A',
        },
      },
      fontFamily: {
        ndot:  ['"NDot 55"', '"NDot 57"', 'monospace'],
        ntype: ['"NType 82"', '"Courier New"', 'monospace'],
        'ntype-mono': ['"NType 82 Mono"', '"Courier New"', 'monospace'],
      },
      letterSpacing: {
        'nothing-label':   '0.10em',
        'nothing-nav':     '0.06em',
        'nothing-display': '0.02em',
      },
      borderRadius: {
        'nothing': '0px',  /* Default sharp */
      },
      animation: {
        'glyph-pulse': 'glyph-pulse 2s linear infinite',
      },
      keyframes: {
        'glyph-pulse': {
          '0%, 100%': { opacity: '1' },
          '50%':       { opacity: '0.3' },
        },
      },
    },
  },
};
Quick Reference Card
Canvas:       Pure black #000000 (dark) / Pure white #FFFFFF (light)
Accent:       Nothing Red #FF3030 — use sparingly, one instance per view
Display font: NDot 55 / NDot 57 — dot matrix, 24px minimum
UI font:      NType 82 — all labels uppercase, 0.08em+ letter-spacing
Mono font:    NType 82 Mono — specs, numbers, data
Borders:      1px solid #2E2E2E — no shadows, no rounded cards
Buttons:      Pill shape (radius: 999px) — filled white or ghost outline
Motion:       cubic-bezier only, 150–400ms — no bounce, no spring
Icons:        Dot-grid, stroke-based, square caps — no filled icon packs
Copy:         Short, technical, declarative — no adjectives without data
Not an official Nothing design system. A curated reference for building Nothing OS–inspired UIs with AI coding agents. Typeface names (NDot, NType 82) are trademarks of Nothing Technology Limited / Colophon Foundry.

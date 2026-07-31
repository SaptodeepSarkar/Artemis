---
name: Artemis Labs
colors:
  surface: '#131318'
  surface-dim: '#131318'
  surface-bright: '#39383e'
  surface-container-lowest: '#0e0e13'
  surface-container-low: '#1b1b20'
  surface-container: '#1f1f25'
  surface-container-high: '#2a292f'
  surface-container-highest: '#35343a'
  on-surface: '#e4e1e9'
  on-surface-variant: '#e0bfbd'
  inverse-surface: '#e4e1e9'
  inverse-on-surface: '#303036'
  outline: '#a88a88'
  outline-variant: '#594140'
  surface-tint: '#ffb3b1'
  primary: '#ffb3b1'
  on-primary: '#680011'
  primary-container: '#b8323a'
  on-primary-container: '#ffd9d7'
  inverse-primary: '#b02c35'
  secondary: '#c5c6cd'
  on-secondary: '#2e3036'
  secondary-container: '#47494f'
  on-secondary-container: '#b7b8bf'
  tertiary: '#a2cbf1'
  on-tertiary: '#003350'
  tertiary-container: '#3e6889'
  on-tertiary-container: '#cbe5ff'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#ffdad8'
  primary-fixed-dim: '#ffb3b1'
  on-primary-fixed: '#410007'
  on-primary-fixed-variant: '#8e1020'
  secondary-fixed: '#e1e2e9'
  secondary-fixed-dim: '#c5c6cd'
  on-secondary-fixed: '#191c21'
  on-secondary-fixed-variant: '#44474c'
  tertiary-fixed: '#cce5ff'
  tertiary-fixed-dim: '#a2cbf1'
  on-tertiary-fixed: '#001e31'
  on-tertiary-fixed-variant: '#1d4a6a'
  background: '#131318'
  on-background: '#e4e1e9'
  surface-variant: '#35343a'
  void-black: '#0A0A0F'
  moon-silver: '#C8C9D0'
  hunt-crimson: '#B8323A'
  pyrenees-frost: '#7BA4C8'
  maquis-green: '#4A7C59'
  gestapo-amber: '#D4A03D'
  surface-elevation: '#12121A'
typography:
  headline-lg:
    fontFamily: Space Grotesk
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Space Grotesk
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
  headline-sm:
    fontFamily: Space Grotesk
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  body-lg:
    fontFamily: Space Grotesk
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-md:
    fontFamily: Space Grotesk
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  code-md:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-caps:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '700'
    lineHeight: 16px
    letterSpacing: 0.1em
  narrative-md:
    fontFamily: Crimson Pro
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
spacing:
  unit: 4px
  gutter: 24px
  margin: 32px
  container-max: 1440px
---

# ARTEMIS LABS | Design System
**Version:** 1.0.0
**Project Codename:** CUTHBERT
**Status:** Operational / Internal Only

---

## Section 1: Brand Essence

### 1.1 Positioning Statement
ARTEMIS LABS is an elite, internal-only Android vulnerability research platform. It is not a commodity tool; it is a tactical environment for the hunt. It operates in the shadows, ahead of the adversary, transforming discovery into defense.

### 1.2 Core Brand Traits
*   **The Hunter, Not the Prey:** Proactive, precise, and lethal in efficiency. We do not wait for breaches; we find the cracks first.
*   **Shadow-Dweller:** Low-profile, dark-mode-first aesthetic. Visuals are atmospheric and tactical, avoiding the "Neon Hacker" cliché.
*   **Cell-Compartmentalized:** Information is organized into six distinct functional cells, ensuring operational security and clarity.
*   **The Human Behind the Machine:** Inspired by Virginia Hall. Technology is the tool (the prosthetic), but the human intellect is the weapon.

---

## Section 2: Logo System

### 2.1 Primary Mark: The Hunter’s Crescent
The mark is a stylized crescent moon (the bow of Artemis) that functions as a magnifying lens and crosshair. 
*   **Symbolism:** The negative space forms a downward-pointing arrow (the payload), while an angular "break" at the tip suggests a vulnerability found.
*   **Geometry:** Built on a 512px circle grid. Inner radius is 38.2% of the outer (Golden Ratio derived). 
*   **The Break:** A 15-degree angular cut at the top-left tip.

---

## Section 3: Color System (The Nocturne)

### 3.1 Primary Palette
The system is exclusively dark-mode.

| Name | Hex | RGB | HSL | Usage |
| :--- | :--- | :--- | :--- | :--- |
| **Void Black** | `#0A0A0F` | `10, 10, 15` | `240, 20%, 5%` | Backgrounds |
| **Moon Silver** | `#C8C9D0` | `200, 201, 208` | `233, 8%, 80%` | Primary Text / Stroke |
| **Hunt Crimson** | `#B8323A` | `184, 50, 58` | `356, 57%, 46%` | Criticals / Primary CTA |
| **Pyrenees Frost** | `#7BA4C8` | `123, 164, 200` | `208, 43%, 63%` | Accents / Focus Rings |
| **Maquis Green** | `#4A7C59` | `74, 124, 89` | `138, 25%, 39%` | Success / Patched |
| **Gestapo Amber**| `#D4A03D` | `212, 160, 61` | `40, 65%, 54%` | Warnings |

### 3.2 Gradient System
*   **The Hunt:** `linear-gradient(180deg, #12121A 0%, #0A0A0F 100%)` (Card Depth)
*   **Crossing the Pyrenees:** `linear-gradient(45deg, #7BA4C8 0%, #B8323A 100%)` (Accent Flare)

---

## Section 4: Typography

### 4.1 Typefaces
*   **Primary:** Space Grotesk (Google Fonts). Geometric, tech-focused, yet readable.
*   **Monospace:** JetBrains Mono. For code blocks, CVEs, and memory addresses.
*   **Narrative:** Crimson Pro. Used sparingly for historical context or long-form intelligence reports.

---

## Section 5: Iconography & UI Elements

### 5.1 Icon Style: "Ghost Line"
*   **Stroke:** 1.5px consistent.
*   **Ends:** Rounded caps/joins.
*   **Fill:** 0% (Always outlined).

### 5.3 Button System
*   **The Hunt (Primary):** Background: `#B8323A`, Text: `#FFFFFF`. Sharp corners.
*   **The Shadow (Secondary):** Border: 1px `#C8C9D0`, Background: transparent.
*   **The Whisper (Ghost):** Text: `#7BA4C8`, no border, hover underline.

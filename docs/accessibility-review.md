# Manager-page accessibility review

Reviewed September 26, 2026 against the v0.2.0 baseline and this change, using an isolated copy of the runtime data. The scope is Dashboard, My Team, Matchup, Waiver Board, League, Trade Analyzer, and Decision History. This is a targeted browser and source review, not a WCAG conformance certification.

## Findings addressed

| Finding | Change |
| --- | --- |
| Repeated navigation had no skip link | The first Tab reveals **Skip to main content**. Enter focuses the first visible content section. Hidden Dashboard recovery metadata is excluded. |
| Dashboard omitted its document language | The public renderer supplies `lang="en"` when absent and preserves existing language metadata. |
| Current navigation was indicated visually only | The active link also declares `aria-current="page"`. |
| Focus appearance depended on browser defaults | Links, buttons, form controls, disclosures, and the skip target receive an explicit 3px focus outline, with dark-theme and forced-colors rules. |
| Dark-theme green action buttons used white text, measuring 2.97:1 | Dark text on the existing green background measures approximately 6.27:1. Secondary transparent links retain their existing text color. |
| History's primary action retained a light background with light green text in dark mode | Its dark-theme foreground and background now match the active navigation palette. |

The contrast target for ordinary text is 4.5:1; large text uses 3:1. See W3C's [Contrast (Minimum)](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum) and [Bypass Blocks](https://www.w3.org/WAI/WCAG22/Understanding/bypass-blocks) guidance.

## Validation and limits

- Windows PowerShell 5.1 fixtures execute the production accessibility renderer, including existing fragment IDs, existing language, idempotence, preserved diagnostic evidence, and hidden recovery markup.
- BF-885/BF-912 now require language metadata, a focusable skip destination, and the correct current navigation link on all seven manager pages.
- Browser keyboard checks use Tab, Enter, and subsequent Tab to confirm skip behavior. Page titles, landmarks, heading outlines, control labels, default dark-theme text colors, and 320px reflow are inspected.
- Contrast sampling uses computed foreground colors and the nearest opaque ancestor background. It is not a complete audit of blended colors, every hover state, every dynamic trade result, or charts.
- The brand and page title currently both use H1. This is recorded as an outline refinement opportunity; multiple H1 elements alone are not treated as proof of a WCAG failure.
- A real screen-reader session, comprehensive light-theme/forced-colors testing, and the human task below remain pending. Automated route timings do not establish human usability or task completion time.

## Timed human task

Use the updated app with your normal input method. Start a timer when Dashboard finishes loading. Do not refresh evidence or submit a roster transaction for this exercise.

1. Identify the highest-priority item and explain whether Butler says it is ready to use or needs a fresh review.
2. Open Waiver Board and find whether Butler recommends a move or no move. State the reason shown.
3. Open History, identify the latest recorded outcome, and return to Dashboard.

Stop the timer when back on Dashboard. Record elapsed time, whether all three answers were clear, and any confusing label or navigation step. A useful first target is under five minutes without assistance; this is a product usability target, not a WCAG requirement.

Optional keyboard pass: reload Dashboard, press Tab then Enter to skip navigation, continue with Tab/Shift+Tab, and open a disclosure with Enter. Report any lost focus, invisible indicator, or place you cannot leave.

**Human result: pending.** Record the observed result here only after an actual user run.

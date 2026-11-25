# DT-DES-07-R1 — Overlay test navigation correction

CI 45539881765: build/test and ordinary emulator capture succeeded. Real overlay capture failed at onboarding before permission, split approval, or automatic input. Diagnostic screenshot shows the onboarding screen above its continue button; the original test only searched visible nodes and never scrolled. No actual overlay design states were captured.

Correction: instrumented probe test only. Search visible controls and perform bounded forward scroll on actual scrollable widgets of the expected app/system Settings package. Use actual consent, manual system switch, confirmation, and pair selection; do not write app preferences or permission settings. Record substage, scroll outcomes, fixture-only visible control labels, and screenshot on failure. Try only supported split command advertised by emulator help. All capture-state checks, emergency stop, cleanup, and manifest/production code unchanged. New CI is required before asserting any overlay capture or physical-device success.

DES-04 remains not forced because foregrounding the controller invalidates real pair approval. No device QA, publishing, or readiness change.

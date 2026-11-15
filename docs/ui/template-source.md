# WiSafe's native template adaptation

Design source: **Modernize Free React MUI Dashboard**, by AdminMart,
[listed on 21st.dev](https://21st.dev/@adminmart/templates/modernize-free-react-mui-dashboard).

The Android interface adapts the MIT-licensed upstream source at
[`4de5f2a10f6b64fdf8090bbd5a25ad52fcd67226`](https://github.com/adminmart/modernize-react-lite/tree/4de5f2a10f6b64fdf8090bbd5a25ad52fcd67226).
The hosted 21st download is not bundled. Reference files inspected:

- `package/src/theme/DefaultColors.js`: palette, outlines, button shape, card shadows.
- `package/src/theme/Typography.js`: heading and body hierarchy.
- `package/src/components/shared/DashboardCard.js`: title/content/action grouping.
- `package/src/views/dashboard/components/YearlyBreakup.js`: tinted metric grouping.

`ModernizeTheme.kt` holds adapted color tokens. Primary buttons and small blue
text use a darker blue for contrast. Android uses the platform sans-serif font;
the web template's Plus Jakarta Sans font is not bundled. Rounded dashboard cards,
soft surfaces, spacing and heading hierarchy are translated into native views.

This is a **native Kotlin adaptation**, not an installation of React/MUI or a
conversion to React Native. Wi-Fi collection remains native. Educational figures
remain WiSafe's original vector drawings. All network counts, scores and scan
ages come from actual collection/API state; template sample statistics and charts
are not included.

Copyright (c) 2025 AdminMart. Full MIT notice is packaged in
[`modernize.txt`](../../android/app/src/main/assets/licenses/modernize.txt) and
available in the app under Setup → Open-source licenses.

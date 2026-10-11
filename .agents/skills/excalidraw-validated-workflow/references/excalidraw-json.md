# Excalidraw JSON essentials

Use top-level keys `type: "excalidraw"`, `version: 2`, `source: "omp"`,
`elements`, `appState`, and `files`.

Every visible element needs a unique `id`, `type`, finite `x`, `y`, `width`,
`height`, and `angle`, plus normal Excalidraw style/version fields. Text uses
`text`, `originalText`, `fontSize`, `textAlign`, `verticalAlign`, and optionally
`containerId`.

Arrow `points` are relative to its `x` and `y`. A connected arrow uses both
`startBinding.elementId` and `endBinding.elementId`; both IDs must exist.
Include matching arrow entries in endpoint shapes' `boundElements`.

Use at least 18 px body text and 24 px titles. Prefer a 4:3 composition, clear
whitespace, restrained colors, strong contrast, and no emoji.

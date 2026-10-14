# palette/ 目录的来源与许可

本目录下的 `hct/`、`utils/` 是从生产工程 `OPPO-Watch2-优化资料与交接-20261001` 的
`工程/work/watchface-theme-20261008/vendor/` 直接复制的 Material Color Utilities（MCU）
派生源码，用于腕上工坊本地离线复算表盘主题颜色。

- 上游：Google Material Color Utilities（Apache License 2.0）。
- 复制范围：`hct/`（Cam16、Hct、HctSolver、ViewingConditions）与 `utils/`（ColorUtils、MathUtils），
  即 `fromSeed` 派生所需的最小集合；`quantize/`、`score/` 只服务 `fromPixels`，不在本轮范围。
- 改动：仅把 `package hct;` / `package utils;` 改为
  `package dev.codex.watchworkshop.palette.hct;` / `dev.codex.watchworkshop.palette.utils;`，
  并同步 `import utils.*` 前缀；其余代码逐行保持原样，Apache-2.0 版权头原样保留。
- 未改动生产工程与生产 helper；本目录仅供腕上工坊 app 本机离线预览使用。

`ThemePalettePreview.java` 为新增代码，`fromSeed` 的计算与生产
`dev.codex.watchtheme.ThemePalette.fromSeed` 使用相同的色度与色调参数。

# JLShell 品牌图标

2026-10-14，用户选定版。按用户提供的终端 + JLShell 字标参考图定稿：深蓝圆角底，上方白色 >_ 命令提示符，下方蓝色 JL 与白色 Shell。去除外框、字形周围的发光轮廓、霓虹描边和光晕，保留内部适度的蓝色渐变；圆角外透明。

母版：`ui/src/main/resources/icons/brand/jlshell.png`，1024×1024 RGBA。

由内置 imagegen 生成设计；导出只做等比缩放与格式封装，没有重新绘制每种尺寸。生成提示词：

> Precise cleanup edit of the approved JLShell app icon reference. Preserve composition, letter proportions, rounded-square silhouette, deep navy background, large white >_ in the upper half and exact text JLShell below (JL cyan-to-blue, Shell white). Remove all glowing outlines: cyan luminous tile border, neon rim lighting, outer halo and glow surrounding letters. Use clean matte navy to the smoothly antialiased edge. Preserve restrained internal JL blue gradient without emission. White glyphs have no aura. Transparent exterior, no white surrounding background, external shadow, fringe or stray pixels. Do not redesign or add anything.

使用内置 imagegen 编辑用户选定参考图，仅消除发光效果；按本次确认同步更新母版和所有平台导出。

重新导出：`python3 .github/scripts/export_brand_icons.py /path/to/approved.png`。脚本在 macOS 使用系统 sips 等比缩放，并以 PNG 表示封装 ICO/ICNS；SVG 仅作嵌图兼容包装。

## 已接入

- 原生 Swing 欢迎页、关于页（设置和菜单共用）、窗口图标。
- JavaFX 回退的欢迎页、标题栏、关于页，以及应用启动页、Stage/Dock 图标。
- `app/src/main/resources/icons/app_icon.png` 和 16/32/48/256 尺寸。
- 旧 `jlshell_app_icon*.png` 名称全部保留为新设计别名，避免遗漏历史引用。
- Windows ICO、macOS ICNS；原构建和 Preview/Release 工作流继续从同一张 `app_icon.png` 导出平台资源。
- `jlshell_app_icon.svg` 为嵌入 PNG 的兼容包装，并非纯路径矢量源。

项目头像代表用户的项目身份，不属于应用品牌图标。

## 关于页入口

GitHub：复用已有 `ui/src/main/resources/icons/about-github.svg` 的 Octocat 原路径，经 Java2D 绘制，代替此前错误的通用插件图标。未从网络下载新素材。

官网：地球图标；下载：向下箭头与托盘；反馈：对话气泡。它们采用统一 24px 几何与当前主题前景色，避免用文件夹、分屏等无关图标代替。

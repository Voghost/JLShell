# JLShell UI Modernization Design

## Goal

Modernize JLShell's visual design to match the quality of professional SSH clients like Termius, improving both dark and light themes while making minimal Java code changes.

## Approach: CSS Overhaul + Targeted Java Adjustments

### 1. Color System

Use CSS custom properties (`-fx-color-*`) for a consistent palette across both themes.

**Dark theme** (Termius-inspired):
| Role | Color | Usage |
|------|-------|-------|
| bg | `#1a1b26` | Main background |
| surface | `#24253a` | Sidebar, toolbar, panels |
| elevated | `#2f3044` | Cards, popups, dropdowns |
| border | `#3d3e56` | Subtle borders |
| border-focus | `#7aa2f7` | Focus rings |
| accent | `#7aa2f7` | Primary actions, selections |
| accent-hover | `#89b4fa` | Hover state on accent |
| text | `#c0caf5` | Primary text |
| text-muted | `#565f89` | Secondary/disabled text |
| success | `#9ece6a` | Connected status |
| error | `#f7768e` | Error/disconnected |
| warning | `#e0af68` | Warnings |

**Light theme**:
| Role | Color | Usage |
|------|-------|-------|
| bg | `#f5f6fa` | Main background |
| surface | `#e8e9f0` | Sidebar, toolbar |
| elevated | `#ffffff` | Cards, popups |
| border | `#d1d2da` | Borders |
| border-focus | `#3b82f6` | Focus rings |
| accent | `#3b82f6` | Primary actions |
| accent-hover | `#2563eb` | Hover state |
| text | `#1e1f36` | Primary text |
| text-muted | `#6b7280` | Secondary text |
| success | `#22c55e` | Connected |
| error | `#ef4444` | Error |
| warning | `#f59e0b` | Warnings |

### 2. CSS Improvements

#### Buttons
- Padding: `6 14` (from `3 10`)
- Border radius: `6px`
- Hover: background-color transition 150ms
- Primary buttons: gradient background + subtle shadow
- Icon buttons: rounded hover background block

#### Text Fields
- Padding: `6 12`
- Focus: double border (inner accent, outer semi-transparent glow)
- Placeholder: muted color

#### TreeView / TableView
- Row padding: `6 8`
- Selected row: 2px left accent border line
- Hover row: visible background highlight
- Header: 1px accent bottom border
- Alternate row: very subtle (opacity 0.03)

#### Tabs
- Selected: rounded top corners (`4 4 0 0`) + 2px accent bottom line
- Unselected: muted text
- Hover: subtle background

#### Sidebar
- Selected item: left accent border + background highlight
- Connection icons: rounded colored background block (see Java changes)

#### Dialogs
- Border radius: `10px`
- Subtle shadow
- Button spacing

#### Scrollbars
- Thinner (6px track, 4px thumb)
- Rounded thumb
- Hover expand to 8px

### 3. Java Code Adjustments

#### SidebarView — Connection Icon Backgrounds
- Wrap each connection SVG icon in a StackPane with a colored rounded background
- Color derived from hash of connection name (consistent per-connection)
- Background: rounded rect `8px` radius, accent color at 20% opacity
- Icon: white/contrasting color

#### SftpBrowserPane — Toolbar Buttons
- Add SVG icons to upload/download/refresh/delete/mkdir buttons
- Increase button padding
- Add CSS class `tool-button` for consistent hover styling

#### SessionWorkspaceTab — Tab Graphic
- Add small terminal icon to tab label
- Style close button with hover effect

#### MainWindow — Status Bar
- Add colored dot indicator (green=connected, red=disconnected)
- Add separator between status items
- Style with `status-bar` CSS class

## Files to Modify

### CSS (primary)
- `ui/src/main/resources/css/dark-theme.css` — full rewrite
- `ui/src/main/resources/css/light-theme.css` — full rewrite

### Java (targeted)
- `ui/src/main/java/com/jlshell/ui/view/SidebarView.java` — icon backgrounds
- `ui/src/main/java/com/jlshell/ui/view/SftpBrowserPane.java` — toolbar icons
- `ui/src/main/java/com/jlshell/ui/view/SessionWorkspaceTab.java` — tab graphic
- `ui/src/main/java/com/jlshell/ui/view/MainWindow.java` — status bar

## Out of Scope

- Layout restructuring (sidebar collapse, breadcrumb navigation)
- Custom Tab skin (Chrome-style tabs)
- Panel transition animations
- Glass/blur effects (JavaFX limitations)
- New features or functionality changes

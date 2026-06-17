# UI Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modernize JLShell's visual design to match Termius-quality professional SSH clients, improving both dark and light themes.

**Architecture:** CSS-first approach — rewrite both theme CSS files with a new color system and refined component styles, then make targeted Java adjustments for icon backgrounds, toolbar icons, tab graphics, and status bar.

**Tech Stack:** JavaFX CSS, Java 21, existing SVG icon infrastructure

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `ui/src/main/resources/css/dark-theme.css` | Rewrite | Complete dark theme with new color palette |
| `ui/src/main/resources/css/light-theme.css` | Rewrite | Complete light theme with new color palette |
| `ui/src/main/java/com/jlshell/ui/view/SidebarTreeView.java` | Modify | Connection icon colored backgrounds |
| `ui/src/main/java/com/jlshell/ui/view/SftpBrowserPane.java` | Modify | Toolbar buttons with icons |
| `ui/src/main/java/com/jlshell/ui/view/MainWindow.java` | Modify | Status bar with connection indicator |
| `ui/src/main/java/com/jlshell/ui/view/TerminalWorkspaceView.java` | Modify | Toolbar icons with SVG |

---

### Task 1: Rewrite Dark Theme CSS

**Files:**
- Modify: `ui/src/main/resources/css/dark-theme.css`

- [ ] **Step 1: Write the complete dark-theme.css with new color system**

Replace the entire file with the new Termius-inspired dark theme:

```css
/* ============================================================
   JLShell Dark Theme — Termius-inspired
   Base: #1a1b26  Surface: #24253a  Elevated: #2f3044
   Border: #3d3e56  Accent: #7aa2f7  Text: #c0caf5
   ============================================================ */

/* ---------- Root / Global ---------- */
.root {
    -fx-font-family: "JetBrains Mono", "SF Mono", "Consolas", "PingFang SC", "Microsoft YaHei", monospace;
    -fx-font-size: 13px;
    -fx-background-color: #1a1b26;
    -fx-base: #24253a;
    -fx-accent: #7aa2f7;
    -fx-focus-color: #7aa2f7;
    -fx-faint-focus-color: rgba(122, 162, 247, 0.15);
    -fx-text-fill: #c0caf5;
}

.app-root {
    -fx-background-color: #1a1b26;
}

/* ---------- Menu Bar ---------- */
.menu-bar {
    -fx-background-color: #24253a;
    -fx-border-color: transparent transparent #3d3e56 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 0 6;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
}

.menu {
    -fx-background-color: transparent;
    -fx-padding: 5 10;
}

.menu:hover,
.menu:focused,
.menu:showing {
    -fx-background-color: #2f3044;
    -fx-background-radius: 5;
}

.menu .label,
.menu-item .label {
    -fx-text-fill: #c0caf5;
    -fx-font-size: 13px;
}

.context-menu,
.menu-item {
    -fx-background-color: #2f3044;
    -fx-border-color: #3d3e56;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
    -fx-padding: 3 0;
}

.menu-item {
    -fx-padding: 6 14;
    -fx-border-color: transparent;
}

.menu-item:hover,
.menu-item:focused {
    -fx-background-color: #7aa2f7;
    -fx-background-radius: 4;
}

.menu-item:hover .label,
.menu-item:focused .label {
    -fx-text-fill: #1a1b26;
}

.separator .line {
    -fx-border-color: #3d3e56;
    -fx-border-width: 1 0 0 0;
}

/* ---------- Toolbar ---------- */
.top-shell {
    -fx-background-color: #24253a;
    -fx-border-color: transparent transparent #3d3e56 transparent;
    -fx-border-width: 0 0 1 0;
}

.tool-bar {
    -fx-background-color: #24253a;
    -fx-padding: 4 10;
    -fx-spacing: 3;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
    -fx-border-color: transparent;
}

/* ---------- Buttons ---------- */
.button {
    -fx-background-color: #2f3044;
    -fx-text-fill: #c0caf5;
    -fx-background-radius: 6;
    -fx-border-radius: 6;
    -fx-border-color: #3d3e56;
    -fx-border-width: 1;
    -fx-padding: 5 14;
    -fx-font-size: 12px;
    -fx-font-weight: normal;
    -fx-cursor: hand;
}

.button:hover {
    -fx-background-color: #3d3e56;
    -fx-border-color: #565f89;
}

.button:pressed {
    -fx-background-color: #3b4252;
    -fx-border-color: #7aa2f7;
}

.button:focused {
    -fx-border-color: #7aa2f7;
    -fx-background-color: #2f3044;
}

.button-primary {
    -fx-background-color: linear-gradient(to bottom, #7aa2f7, #5b80d9);
    -fx-text-fill: #1a1b26;
    -fx-border-color: #7aa2f7;
}

.button-primary:hover {
    -fx-background-color: linear-gradient(to bottom, #89b4fa, #7aa2f7);
}

/* ---------- Sidebar ---------- */
.sidebar {
    -fx-background-color: #1f2035;
    -fx-border-color: transparent #3d3e56 transparent transparent;
    -fx-border-width: 0 1 0 0;
    -fx-padding: 8 6;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
}

.sidebar > .label {
    -fx-text-fill: #565f89;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 4 4 6 4;
}

/* ---------- List View ---------- */
.list-view {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-padding: 0;
}

.list-view .scroll-bar:vertical {
    -fx-background-color: transparent;
    -fx-pref-width: 6;
}

.list-view .scroll-bar:vertical .thumb {
    -fx-background-color: #3d3e56;
    -fx-background-radius: 3;
}

.list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #c0caf5;
    -fx-padding: 6 10;
    -fx-font-size: 12px;
    -fx-border-color: transparent;
}

.list-cell:odd {
    -fx-background-color: transparent;
}

.list-cell:hover {
    -fx-background-color: #2f3044;
    -fx-background-radius: 4;
}

.list-cell:filled:selected {
    -fx-background-color: #2f3044;
    -fx-background-radius: 4;
    -fx-border-color: #7aa2f7 transparent #7aa2f7 #7aa2f7;
    -fx-border-width: 0 0 0 2;
    -fx-text-fill: #c0caf5;
}

.list-cell:filled:selected:focused {
    -fx-background-color: #2f3044;
}

/* ---------- Tab Pane ---------- */
.tab-pane {
    -fx-background-color: #1a1b26;
    -fx-tab-min-height: 30px;
    -fx-tab-max-height: 30px;
}

.tab-pane .tab-header-area {
    -fx-background-color: #24253a;
    -fx-border-color: transparent transparent #3d3e56 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 0;
}

.tab-pane .tab-header-area .tab-header-background {
    -fx-background-color: #24253a;
}

.tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-radius: 5 5 0 0;
    -fx-border-color: transparent;
    -fx-padding: 5 18 5 14;
}

.tab-pane .tab .tab-label {
    -fx-text-fill: #565f89;
    -fx-font-size: 12px;
}

.tab-pane .tab:selected {
    -fx-background-color: #1a1b26;
    -fx-border-color: transparent transparent #7aa2f7 transparent;
    -fx-border-width: 0 0 2 0;
    -fx-background-radius: 5 5 0 0;
}

.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #c0caf5;
}

.tab-pane .tab:hover .tab-label {
    -fx-text-fill: #c0caf5;
}

.tab-pane .tab-close-button {
    -fx-background-color: #565f89;
    -fx-shape: "M 0,0 H1 L 4,3 7,0 H8 V1 L 5,4 8,7 V8 H7 L 4,5 1,8 H0 V7 L 3,4 0,1 Z";
    -fx-scale-shape: true;
    -fx-pref-width: 8;
    -fx-pref-height: 8;
}

/* ---------- Workspace / Terminal ---------- */
.workspace-panel {
    -fx-background-color: #1a1b26;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
    -fx-border-color: transparent;
    -fx-padding: 0;
}

.toolbar-strip {
    -fx-background-color: #24253a;
    -fx-border-color: transparent transparent #3d3e56 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 4 10;
    -fx-spacing: 4;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
}

/* ---------- Split Pane ---------- */
.split-pane {
    -fx-background-color: #1a1b26;
    -fx-padding: 0;
}

.split-pane-divider {
    -fx-background-color: #3d3e56;
    -fx-padding: 0 2;
}

/* ---------- Text Fields ---------- */
.text-field,
.password-field,
.text-area {
    -fx-background-color: #1a1b26;
    -fx-text-fill: #c0caf5;
    -fx-prompt-text-fill: #565f89;
    -fx-background-radius: 6;
    -fx-border-radius: 6;
    -fx-border-color: #3d3e56;
    -fx-border-width: 1;
    -fx-padding: 6 12;
    -fx-font-size: 13px;
}

.text-field:focused,
.password-field:focused,
.text-area:focused {
    -fx-border-color: #7aa2f7;
    -fx-background-color: #1a1b26;
}

/* ---------- Combo Box ---------- */
.combo-box {
    -fx-background-color: #2f3044;
    -fx-border-color: #3d3e56;
    -fx-border-radius: 6;
    -fx-background-radius: 6;
    -fx-padding: 3 6;
}

.combo-box .list-cell {
    -fx-text-fill: #c0caf5;
    -fx-background-color: transparent;
    -fx-padding: 4 8;
}

.combo-box-popup .list-view {
    -fx-background-color: #2f3044;
    -fx-border-color: #3d3e56;
    -fx-border-radius: 6;
    -fx-background-radius: 6;
}

/* ---------- Labels ---------- */
.label {
    -fx-text-fill: #c0caf5;
}

/* ---------- Status Bar ---------- */
.status-bar {
    -fx-background-color: #24253a;
    -fx-border-color: #3d3e56 transparent transparent transparent;
    -fx-border-width: 1 0 0 0;
    -fx-text-fill: #565f89;
    -fx-font-size: 11px;
    -fx-padding: 4 12;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
}

.status-indicator {
    -fx-background-radius: 6;
    -fx-min-width: 8;
    -fx-min-height: 8;
    -fx-max-width: 8;
    -fx-max-height: 8;
}

.status-indicator-connected {
    -fx-background-color: #9ece6a;
}

.status-indicator-disconnected {
    -fx-background-color: #565f89;
}

/* ---------- Dialog / Alert ---------- */
.dialog-pane {
    -fx-background-color: #2f3044;
    -fx-border-color: #3d3e56;
    -fx-border-radius: 10;
    -fx-background-radius: 10;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 12, 0, 0, 4);
}

.dialog-pane .header-panel {
    -fx-background-color: #24253a;
    -fx-background-radius: 10 10 0 0;
}

.dialog-pane .header-panel .label {
    -fx-text-fill: #c0caf5;
    -fx-font-size: 14px;
    -fx-font-weight: bold;
}

.dialog-pane .content.label {
    -fx-text-fill: #565f89;
    -fx-font-size: 13px;
}

.dialog-pane .button-bar .button {
    -fx-min-width: 80px;
}

/* ---------- Scroll Bar ---------- */
.scroll-bar {
    -fx-background-color: transparent;
}

.scroll-bar .thumb {
    -fx-background-color: #3d3e56;
    -fx-background-radius: 4;
}

.scroll-bar .thumb:hover {
    -fx-background-color: #565f89;
}

.scroll-bar .increment-button,
.scroll-bar .decrement-button {
    -fx-background-color: transparent;
    -fx-padding: 0;
}

.scroll-bar .increment-arrow,
.scroll-bar .decrement-arrow {
    -fx-shape: "";
    -fx-padding: 0;
}

/* ---------- Icon Buttons (sidebar action bar) ---------- */
.icon-btn {
    -fx-background-color: transparent;
    -fx-text-fill: #565f89;
    -fx-background-radius: 5;
    -fx-border-radius: 5;
    -fx-border-color: transparent;
    -fx-padding: 4 8;
    -fx-font-size: 13px;
    -fx-min-width: 28px;
    -fx-min-height: 26px;
    -fx-cursor: hand;
}

.action-bar-icon {
    -fx-background-color: #565f89;
}

.icon-btn:hover .action-bar-icon {
    -fx-background-color: #c0caf5;
}

.icon-btn-primary .action-bar-icon {
    -fx-background-color: #1a1b26;
}

.icon-btn:hover {
    -fx-background-color: #2f3044;
    -fx-text-fill: #c0caf5;
}

.icon-btn:pressed {
    -fx-background-color: #3b4252;
    -fx-text-fill: #c0caf5;
}

.icon-btn-primary {
    -fx-background-color: #7aa2f7;
    -fx-text-fill: #1a1b26;
    -fx-border-color: transparent;
}

.icon-btn-primary:hover {
    -fx-background-color: #89b4fa;
}

/* ---------- Sidebar action bar ---------- */
.sidebar-action-bar {
    -fx-background-color: #1f2035;
    -fx-border-color: #3d3e56 transparent transparent transparent;
    -fx-border-width: 1 0 0 0;
    -fx-padding: 5 5;
    -fx-alignment: center-left;
}

/* ---------- Sidebar section label ---------- */
.sidebar-section-label {
    -fx-text-fill: #565f89;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 10 10 4 10;
}

/* ---------- Connection list cell ---------- */
.conn-cell-name {
    -fx-text-fill: #c0caf5;
    -fx-font-size: 12px;
}

.conn-cell-summary {
    -fx-text-fill: #565f89;
    -fx-font-size: 11px;
}

.list-cell:filled:selected .conn-cell-name {
    -fx-text-fill: #c0caf5;
}

.list-cell:filled:selected .conn-cell-summary {
    -fx-text-fill: #89b4fa;
}

/* ---------- Tree View (sidebar) ---------- */
.tree-view {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-padding: 0;
}

.tree-view .scroll-bar:vertical {
    -fx-background-color: transparent;
    -fx-pref-width: 6;
}

.tree-view .scroll-bar:vertical .thumb {
    -fx-background-color: #3d3e56;
    -fx-background-radius: 3;
}

.tree-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #c0caf5;
    -fx-padding: 3 6;
    -fx-font-size: 12px;
    -fx-border-color: transparent;
    -fx-indent: 14;
}

.tree-cell:hover {
    -fx-background-color: #2f3044;
    -fx-background-radius: 4;
}

.tree-cell:filled:selected {
    -fx-background-color: #2f3044;
    -fx-background-radius: 4;
    -fx-border-color: #7aa2f7 transparent #7aa2f7 #7aa2f7;
    -fx-border-width: 0 0 0 2;
}

.tree-cell:filled:selected:focused {
    -fx-background-color: #2f3044;
}

.tree-cell .tree-disclosure-node .arrow {
    -fx-background-color: #565f89;
    -fx-padding: 3;
}

.tree-cell:filled:selected .tree-disclosure-node .arrow {
    -fx-background-color: #c0caf5;
}

/* Folder item */
.folder-item {
    -fx-alignment: center-left;
    -fx-spacing: 5;
}

.folder-item-name {
    -fx-text-fill: #c0caf5;
    -fx-font-size: 12px;
}

.tree-cell:filled:selected .folder-item-name {
    -fx-text-fill: #c0caf5;
}

/* Connection item */
.connection-item {
    -fx-alignment: center-left;
    -fx-spacing: 8;
}

.tree-cell:filled:selected .conn-cell-name {
    -fx-text-fill: #c0caf5;
}

.tree-cell:filled:selected .conn-cell-summary {
    -fx-text-fill: #89b4fa;
}

/* ---------- Action bar icon color ---------- */
.action-bar-icon {
    -fx-background-color: #565f89;
}

.icon-btn:hover .action-bar-icon {
    -fx-background-color: #c0caf5;
}

.icon-btn:pressed .action-bar-icon,
.icon-btn-primary .action-bar-icon {
    -fx-background-color: #1a1b26;
}

/* ---------- Sidebar icon colors ---------- */
.sidebar-icon-folder {
    -fx-background-color: #e0af68;
}

.sidebar-icon-server {
    -fx-background-color: #7aa2f7;
}

.sidebar-icon-terminal {
    -fx-background-color: #9ece6a;
}

.tree-cell:filled:selected .sidebar-icon-folder,
.tree-cell:filled:selected .sidebar-icon-server,
.tree-cell:filled:selected .sidebar-icon-terminal {
    -fx-background-color: #c0caf5;
}

/* ---------- Connection icon badge ---------- */
.conn-icon-badge {
    -fx-background-radius: 6;
    -fx-padding: 3;
}

/* ---------- SFTP Browser ---------- */
.sftp-pane-header {
    -fx-text-fill: #565f89;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 0 0 4 0;
}

.sftp-nav-bar {
    -fx-background-color: #24253a;
    -fx-border-color: transparent transparent #3d3e56 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 4 6;
    -fx-spacing: 5;
    -fx-alignment: center-left;
}

.sftp-path-label {
    -fx-text-fill: #565f89;
    -fx-font-size: 12px;
}

.sftp-section-label {
    -fx-text-fill: #565f89;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 6 6 3 6;
}

.sftp-file-table {
    -fx-background-color: #1a1b26;
    -fx-border-color: #3d3e56;
    -fx-border-width: 1;
    -fx-border-radius: 6;
    -fx-background-radius: 6;
}

.sftp-file-table .column-header-background {
    -fx-background-color: #24253a;
}

.sftp-file-table .column-header {
    -fx-background-color: transparent;
    -fx-border-color: transparent #3d3e56 transparent transparent;
    -fx-border-width: 0 1 0 0;
}

.sftp-file-table .column-header .label {
    -fx-text-fill: #565f89;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-alignment: center-left;
}

.sftp-file-table .table-row-cell {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-table-cell-border-color: transparent;
}

.sftp-file-table .table-row-cell:odd {
    -fx-background-color: derive(#1a1b26, 3%);
}

.sftp-file-table .table-row-cell:hover {
    -fx-background-color: #2f3044;
}

.sftp-file-table .table-row-cell:selected {
    -fx-background-color: #2f3044;
    -fx-border-color: #7aa2f7 transparent #7aa2f7 #7aa2f7;
    -fx-border-width: 0 0 0 2;
}

.sftp-file-table .table-cell {
    -fx-text-fill: #c0caf5;
    -fx-font-size: 12px;
    -fx-padding: 4 8;
    -fx-border-color: transparent;
}

.sftp-file-table .table-row-cell:selected .table-cell {
    -fx-text-fill: #c0caf5;
}

/* ---------- SFTP toolbar button ---------- */
.tool-button {
    -fx-background-color: transparent;
    -fx-text-fill: #565f89;
    -fx-background-radius: 5;
    -fx-border-radius: 5;
    -fx-border-color: transparent;
    -fx-padding: 4 10;
    -fx-font-size: 12px;
    -fx-cursor: hand;
    -fx-content-display: left;
    -fx-graphic-text-gap: 5;
}

.tool-button:hover {
    -fx-background-color: #2f3044;
    -fx-text-fill: #c0caf5;
}

.tool-button:pressed {
    -fx-background-color: #3b4252;
}

.tool-button .action-bar-icon {
    -fx-background-color: #565f89;
}

.tool-button:hover .action-bar-icon {
    -fx-background-color: #c0caf5;
}

/* ---------- Sidebar project label ---------- */
.sidebar-project-label {
    -fx-text-fill: #7aa2f7;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 0 10 6 10;
}

/* ---------- Plugin styles ---------- */
.plugin-name {
    -fx-text-fill: #c0caf5;
    -fx-font-size: 13px;
    -fx-font-weight: bold;
}

.plugin-desc {
    -fx-text-fill: #565f89;
    -fx-font-size: 11px;
}

/* ---------- Form grid ---------- */
.form-grid {
    -fx-hgap: 14;
    -fx-vgap: 12;
}
```

- [ ] **Step 2: Compile and verify dark theme loads**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit dark theme**

```bash
git add ui/src/main/resources/css/dark-theme.css
git commit -m "style: rewrite dark theme with Termius-inspired color palette"
```

---

### Task 2: Rewrite Light Theme CSS

**Files:**
- Modify: `ui/src/main/resources/css/light-theme.css`

- [ ] **Step 1: Write the complete light-theme.css with new color system**

Replace the entire file with the new light theme matching the dark theme's structure:

```css
/* ============================================================
   JLShell Light Theme — Termius-inspired
   Base: #f5f6fa  Surface: #e8e9f0  Elevated: #ffffff
   Border: #d1d2da  Accent: #3b82f6  Text: #1e1f36
   ============================================================ */

/* ---------- Root / Global ---------- */
.root {
    -fx-font-family: "SF Pro Display", "PingFang SC", "Microsoft YaHei", sans-serif;
    -fx-font-size: 13px;
    -fx-background-color: #f5f6fa;
    -fx-base: #ffffff;
    -fx-accent: #3b82f6;
    -fx-focus-color: #3b82f6;
    -fx-faint-focus-color: rgba(59, 130, 246, 0.15);
    -fx-text-fill: #1e1f36;
}

.app-root {
    -fx-background-color: #f5f6fa;
}

/* ---------- Menu Bar ---------- */
.menu-bar {
    -fx-background-color: #ffffff;
    -fx-border-color: transparent transparent #d1d2da transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 0 6;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
}

.menu {
    -fx-background-color: transparent;
    -fx-padding: 5 10;
}

.menu:hover,
.menu:focused,
.menu:showing {
    -fx-background-color: #e8e9f0;
    -fx-background-radius: 5;
}

.menu .label,
.menu-item .label {
    -fx-text-fill: #1e1f36;
    -fx-font-size: 13px;
}

.context-menu,
.menu-item {
    -fx-background-color: #ffffff;
    -fx-border-color: #d1d2da;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
    -fx-padding: 3 0;
}

.menu-item {
    -fx-padding: 6 14;
    -fx-border-color: transparent;
}

.menu-item:hover,
.menu-item:focused {
    -fx-background-color: #3b82f6;
    -fx-background-radius: 4;
}

.menu-item:hover .label,
.menu-item:focused .label {
    -fx-text-fill: #ffffff;
}

.separator .line {
    -fx-border-color: #d1d2da;
    -fx-border-width: 1 0 0 0;
}

/* ---------- Toolbar ---------- */
.top-shell {
    -fx-background-color: #ffffff;
    -fx-border-color: transparent transparent #d1d2da transparent;
    -fx-border-width: 0 0 1 0;
}

.tool-bar {
    -fx-background-color: #ffffff;
    -fx-padding: 4 10;
    -fx-spacing: 3;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
    -fx-border-color: transparent;
}

/* ---------- Buttons ---------- */
.button {
    -fx-background-color: #ffffff;
    -fx-text-fill: #1e1f36;
    -fx-background-radius: 6;
    -fx-border-radius: 6;
    -fx-border-color: #d1d2da;
    -fx-border-width: 1;
    -fx-padding: 5 14;
    -fx-font-size: 12px;
    -fx-font-weight: normal;
    -fx-cursor: hand;
}

.button:hover {
    -fx-background-color: #e8e9f0;
    -fx-border-color: #9ca3af;
}

.button:pressed {
    -fx-background-color: #dbeafe;
    -fx-border-color: #3b82f6;
}

.button:focused {
    -fx-border-color: #3b82f6;
    -fx-background-color: #ffffff;
}

.button-primary {
    -fx-background-color: linear-gradient(to bottom, #3b82f6, #2563eb);
    -fx-text-fill: #ffffff;
    -fx-border-color: #3b82f6;
}

.button-primary:hover {
    -fx-background-color: linear-gradient(to bottom, #60a5fa, #3b82f6);
}

/* ---------- Sidebar ---------- */
.sidebar {
    -fx-background-color: #eef0f6;
    -fx-border-color: transparent #d1d2da transparent transparent;
    -fx-border-width: 0 1 0 0;
    -fx-padding: 8 6;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
}

.sidebar > .label {
    -fx-text-fill: #6b7280;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 4 4 6 4;
}

/* ---------- List View ---------- */
.list-view {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-padding: 0;
}

.list-view .scroll-bar:vertical {
    -fx-background-color: transparent;
    -fx-pref-width: 6;
}

.list-view .scroll-bar:vertical .thumb {
    -fx-background-color: #d1d2da;
    -fx-background-radius: 3;
}

.list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #1e1f36;
    -fx-padding: 6 10;
    -fx-font-size: 12px;
    -fx-border-color: transparent;
}

.list-cell:odd {
    -fx-background-color: transparent;
}

.list-cell:hover {
    -fx-background-color: #e8e9f0;
    -fx-background-radius: 4;
}

.list-cell:filled:selected {
    -fx-background-color: #dbeafe;
    -fx-background-radius: 4;
    -fx-border-color: #3b82f6 transparent #3b82f6 #3b82f6;
    -fx-border-width: 0 0 0 2;
    -fx-text-fill: #1e1f36;
}

.list-cell:filled:selected:focused {
    -fx-background-color: #dbeafe;
}

/* ---------- Tab Pane ---------- */
.tab-pane {
    -fx-background-color: #f5f6fa;
    -fx-tab-min-height: 30px;
    -fx-tab-max-height: 30px;
}

.tab-pane .tab-header-area {
    -fx-background-color: #ffffff;
    -fx-border-color: transparent transparent #d1d2da transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 0;
}

.tab-pane .tab-header-area .tab-header-background {
    -fx-background-color: #ffffff;
}

.tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-radius: 5 5 0 0;
    -fx-border-color: transparent;
    -fx-padding: 5 18 5 14;
}

.tab-pane .tab .tab-label {
    -fx-text-fill: #6b7280;
    -fx-font-size: 12px;
}

.tab-pane .tab:selected {
    -fx-background-color: #f5f6fa;
    -fx-border-color: transparent transparent #3b82f6 transparent;
    -fx-border-width: 0 0 2 0;
    -fx-background-radius: 5 5 0 0;
}

.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #1e1f36;
}

.tab-pane .tab:hover .tab-label {
    -fx-text-fill: #1e1f36;
}

.tab-pane .tab-close-button {
    -fx-background-color: #9ca3af;
    -fx-shape: "M 0,0 H1 L 4,3 7,0 H8 V1 L 5,4 8,7 V8 H7 L 4,5 1,8 H0 V7 L 3,4 0,1 Z";
    -fx-scale-shape: true;
    -fx-pref-width: 8;
    -fx-pref-height: 8;
}

/* ---------- Workspace / Terminal ---------- */
.workspace-panel {
    -fx-background-color: #f5f6fa;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
    -fx-border-color: transparent;
    -fx-padding: 0;
}

.toolbar-strip {
    -fx-background-color: #ffffff;
    -fx-border-color: transparent transparent #d1d2da transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 4 10;
    -fx-spacing: 4;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
}

/* ---------- Split Pane ---------- */
.split-pane {
    -fx-background-color: #f5f6fa;
    -fx-padding: 0;
}

.split-pane-divider {
    -fx-background-color: #d1d2da;
    -fx-padding: 0 2;
}

/* ---------- Text Fields ---------- */
.text-field,
.password-field,
.text-area {
    -fx-background-color: #ffffff;
    -fx-text-fill: #1e1f36;
    -fx-prompt-text-fill: #9ca3af;
    -fx-background-radius: 6;
    -fx-border-radius: 6;
    -fx-border-color: #d1d2da;
    -fx-border-width: 1;
    -fx-padding: 6 12;
    -fx-font-size: 13px;
}

.text-field:focused,
.password-field:focused,
.text-area:focused {
    -fx-border-color: #3b82f6;
    -fx-background-color: #ffffff;
}

/* ---------- Combo Box ---------- */
.combo-box {
    -fx-background-color: #ffffff;
    -fx-border-color: #d1d2da;
    -fx-border-radius: 6;
    -fx-background-radius: 6;
    -fx-padding: 3 6;
}

.combo-box .list-cell {
    -fx-text-fill: #1e1f36;
    -fx-background-color: transparent;
    -fx-padding: 4 8;
}

.combo-box-popup .list-view {
    -fx-background-color: #ffffff;
    -fx-border-color: #d1d2da;
    -fx-border-radius: 6;
    -fx-background-radius: 6;
}

/* ---------- Labels ---------- */
.label {
    -fx-text-fill: #1e1f36;
}

/* ---------- Status Bar ---------- */
.status-bar {
    -fx-background-color: #ffffff;
    -fx-border-color: #d1d2da transparent transparent transparent;
    -fx-border-width: 1 0 0 0;
    -fx-text-fill: #6b7280;
    -fx-font-size: 11px;
    -fx-padding: 4 12;
    -fx-background-radius: 0;
    -fx-border-radius: 0;
}

.status-indicator {
    -fx-background-radius: 6;
    -fx-min-width: 8;
    -fx-min-height: 8;
    -fx-max-width: 8;
    -fx-max-height: 8;
}

.status-indicator-connected {
    -fx-background-color: #22c55e;
}

.status-indicator-disconnected {
    -fx-background-color: #9ca3af;
}

/* ---------- Dialog / Alert ---------- */
.dialog-pane {
    -fx-background-color: #ffffff;
    -fx-border-color: #d1d2da;
    -fx-border-radius: 10;
    -fx-background-radius: 10;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 12, 0, 0, 4);
}

.dialog-pane .header-panel {
    -fx-background-color: #e8e9f0;
    -fx-background-radius: 10 10 0 0;
}

.dialog-pane .header-panel .label {
    -fx-text-fill: #1e1f36;
    -fx-font-size: 14px;
    -fx-font-weight: bold;
}

.dialog-pane .content.label {
    -fx-text-fill: #6b7280;
    -fx-font-size: 13px;
}

.dialog-pane .button-bar .button {
    -fx-min-width: 80px;
}

/* ---------- Scroll Bar ---------- */
.scroll-bar {
    -fx-background-color: transparent;
}

.scroll-bar .thumb {
    -fx-background-color: #d1d2da;
    -fx-background-radius: 4;
}

.scroll-bar .thumb:hover {
    -fx-background-color: #9ca3af;
}

.scroll-bar .increment-button,
.scroll-bar .decrement-button {
    -fx-background-color: transparent;
    -fx-padding: 0;
}

.scroll-bar .increment-arrow,
.scroll-bar .decrement-arrow {
    -fx-shape: "";
    -fx-padding: 0;
}

/* ---------- Icon Buttons ---------- */
.icon-btn {
    -fx-background-color: transparent;
    -fx-text-fill: #6b7280;
    -fx-background-radius: 5;
    -fx-border-radius: 5;
    -fx-border-color: transparent;
    -fx-padding: 4 8;
    -fx-font-size: 13px;
    -fx-min-width: 28px;
    -fx-min-height: 26px;
    -fx-cursor: hand;
}

.action-bar-icon {
    -fx-background-color: #6b7280;
}

.icon-btn:hover .action-bar-icon {
    -fx-background-color: #1e1f36;
}

.icon-btn-primary .action-bar-icon {
    -fx-background-color: #ffffff;
}

.icon-btn:hover {
    -fx-background-color: #e8e9f0;
    -fx-text-fill: #1e1f36;
}

.icon-btn:pressed {
    -fx-background-color: #dbeafe;
    -fx-text-fill: #1e3a5f;
}

.icon-btn-primary {
    -fx-background-color: #3b82f6;
    -fx-text-fill: #ffffff;
    -fx-border-color: transparent;
}

.icon-btn-primary:hover {
    -fx-background-color: #2563eb;
}

/* ---------- Sidebar action bar ---------- */
.sidebar-action-bar {
    -fx-background-color: #eef0f6;
    -fx-border-color: #d1d2da transparent transparent transparent;
    -fx-border-width: 1 0 0 0;
    -fx-padding: 5 5;
    -fx-alignment: center-left;
}

/* ---------- Sidebar section label ---------- */
.sidebar-section-label {
    -fx-text-fill: #9ca3af;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 10 10 4 10;
}

/* ---------- Connection list cell ---------- */
.conn-cell-name {
    -fx-text-fill: #1e1f36;
    -fx-font-size: 12px;
}

.conn-cell-summary {
    -fx-text-fill: #6b7280;
    -fx-font-size: 11px;
}

.list-cell:filled:selected .conn-cell-name {
    -fx-text-fill: #1e1f36;
}

.list-cell:filled:selected .conn-cell-summary {
    -fx-text-fill: #3b82f6;
}

/* ---------- SFTP Browser ---------- */
.sftp-pane-header {
    -fx-text-fill: #6b7280;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 0 0 4 0;
}

.sftp-nav-bar {
    -fx-background-color: #ffffff;
    -fx-border-color: transparent transparent #d1d2da transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 4 6;
    -fx-spacing: 5;
    -fx-alignment: center-left;
}

.sftp-path-label {
    -fx-text-fill: #6b7280;
    -fx-font-size: 12px;
}

.sftp-section-label {
    -fx-text-fill: #9ca3af;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 6 6 3 6;
}

.sftp-file-table {
    -fx-background-color: #ffffff;
    -fx-border-color: #d1d2da;
    -fx-border-width: 1;
    -fx-border-radius: 6;
    -fx-background-radius: 6;
}

.sftp-file-table .column-header-background {
    -fx-background-color: #e8e9f0;
}

.sftp-file-table .column-header {
    -fx-background-color: transparent;
    -fx-border-color: transparent #d1d2da transparent transparent;
    -fx-border-width: 0 1 0 0;
}

.sftp-file-table .column-header .label {
    -fx-text-fill: #6b7280;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-alignment: center-left;
}

.sftp-file-table .table-row-cell {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-table-cell-border-color: transparent;
}

.sftp-file-table .table-row-cell:odd {
    -fx-background-color: derive(#f5f6fa, -2%);
}

.sftp-file-table .table-row-cell:hover {
    -fx-background-color: #e8e9f0;
}

.sftp-file-table .table-row-cell:selected {
    -fx-background-color: #dbeafe;
    -fx-border-color: #3b82f6 transparent #3b82f6 #3b82f6;
    -fx-border-width: 0 0 0 2;
}

.sftp-file-table .table-cell {
    -fx-text-fill: #1e1f36;
    -fx-font-size: 12px;
    -fx-padding: 4 8;
    -fx-border-color: transparent;
}

.sftp-file-table .table-row-cell:selected .table-cell {
    -fx-text-fill: #1e1f36;
}

/* ---------- SFTP toolbar button ---------- */
.tool-button {
    -fx-background-color: transparent;
    -fx-text-fill: #6b7280;
    -fx-background-radius: 5;
    -fx-border-radius: 5;
    -fx-border-color: transparent;
    -fx-padding: 4 10;
    -fx-font-size: 12px;
    -fx-cursor: hand;
    -fx-content-display: left;
    -fx-graphic-text-gap: 5;
}

.tool-button:hover {
    -fx-background-color: #e8e9f0;
    -fx-text-fill: #1e1f36;
}

.tool-button:pressed {
    -fx-background-color: #dbeafe;
}

.tool-button .action-bar-icon {
    -fx-background-color: #6b7280;
}

.tool-button:hover .action-bar-icon {
    -fx-background-color: #1e1f36;
}

/* ---------- Sidebar project label ---------- */
.sidebar-project-label {
    -fx-text-fill: #3b82f6;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-padding: 0 10 6 10;
}

/* ---------- Tree View (sidebar) ---------- */
.tree-view {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-padding: 0;
}

.tree-view .scroll-bar:vertical {
    -fx-background-color: transparent;
    -fx-pref-width: 6;
}

.tree-view .scroll-bar:vertical .thumb {
    -fx-background-color: #d1d2da;
    -fx-background-radius: 3;
}

.tree-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #1e1f36;
    -fx-padding: 3 6;
    -fx-font-size: 12px;
    -fx-border-color: transparent;
    -fx-indent: 14;
}

.tree-cell:hover {
    -fx-background-color: #e8e9f0;
    -fx-background-radius: 4;
}

.tree-cell:filled:selected {
    -fx-background-color: #dbeafe;
    -fx-background-radius: 4;
    -fx-border-color: #3b82f6 transparent #3b82f6 #3b82f6;
    -fx-border-width: 0 0 0 2;
}

.tree-cell:filled:selected:focused {
    -fx-background-color: #dbeafe;
}

.tree-cell .tree-disclosure-node .arrow {
    -fx-background-color: #6b7280;
    -fx-padding: 3;
}

.tree-cell:filled:selected .tree-disclosure-node .arrow {
    -fx-background-color: #1e1f36;
}

/* Folder item */
.folder-item {
    -fx-alignment: center-left;
    -fx-spacing: 5;
}

.folder-item-name {
    -fx-text-fill: #1e1f36;
    -fx-font-size: 12px;
}

.tree-cell:filled:selected .folder-item-name {
    -fx-text-fill: #1e1f36;
}

/* Connection item */
.connection-item {
    -fx-alignment: center-left;
    -fx-spacing: 8;
}

.tree-cell:filled:selected .conn-cell-name {
    -fx-text-fill: #1e1f36;
}

.tree-cell:filled:selected .conn-cell-summary {
    -fx-text-fill: #3b82f6;
}

/* ---------- Action bar icon color ---------- */
.action-bar-icon {
    -fx-background-color: #6b7280;
}

.icon-btn:hover .action-bar-icon {
    -fx-background-color: #1e1f36;
}

.icon-btn:pressed .action-bar-icon,
.icon-btn-primary .action-bar-icon {
    -fx-background-color: #ffffff;
}

/* ---------- Sidebar icon colors ---------- */
.sidebar-icon-folder {
    -fx-background-color: #f59e0b;
}

.sidebar-icon-server {
    -fx-background-color: #3b82f6;
}

.sidebar-icon-terminal {
    -fx-background-color: #22c55e;
}

.tree-cell:filled:selected .sidebar-icon-folder,
.tree-cell:filled:selected .sidebar-icon-server,
.tree-cell:filled:selected .sidebar-icon-terminal {
    -fx-background-color: #1e1f36;
}

/* ---------- Connection icon badge ---------- */
.conn-icon-badge {
    -fx-background-radius: 6;
    -fx-padding: 3;
}

/* ---------- Plugin styles ---------- */
.plugin-name {
    -fx-text-fill: #1e1f36;
    -fx-font-size: 13px;
    -fx-font-weight: bold;
}

.plugin-desc {
    -fx-text-fill: #6b7280;
    -fx-font-size: 11px;
}

/* ---------- Form grid ---------- */
.form-grid {
    -fx-hgap: 14;
    -fx-vgap: 12;
}
```

- [ ] **Step 2: Compile and verify light theme loads**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit light theme**

```bash
git add ui/src/main/resources/css/light-theme.css
git commit -m "style: rewrite light theme with matching color palette"
```

---

### Task 3: Add Connection Icon Colored Backgrounds in Sidebar

**Files:**
- Modify: `ui/src/main/java/com/jlshell/ui/view/SidebarTreeView.java:266-301`

- [ ] **Step 1: Add icon badge helper and update ConnectionItem cell rendering**

In `SidebarTreeView.java`, add a helper method to create a colored icon badge, and modify the `updateItem` method for `ConnectionItem` to wrap the icon in a colored background.

Add this method after `svgPathIcon` (after line 106):

```java
private static Region iconBadge(Region icon, String name) {
    int hue = Math.abs(name.hashCode() % 360);
    String bg = String.format("hsb(%d, 55%%, 65%%)", hue);
    String bgSelected = String.format("hsb(%d, 40%%, 80%%)", hue);
    StackPane badge = new StackPane(icon);
    badge.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 6; -fx-padding: 4;");
    badge.getStyleClass().add("conn-icon-badge");
    badge.setUserData(new String[]{bg, bgSelected});
    return badge;
}
```

Add `import javafx.scene.layout.StackPane;` to imports.

In `updateItem` (around line 288), replace the connection icon creation:

Current code (line 286-296):
```java
Region icon;
if (item instanceof SidebarItem.ConnectionItem ci
        && ci.connectionType() == ConnectionType.LOCAL_SHELL) {
    icon = svgIcon(ICON_TERMINAL, 14, "sidebar-icon-terminal");
} else {
    icon = svgIcon(ICON_SERVER, 14, "sidebar-icon-server");
}
```

Replace with:
```java
Region rawIcon;
if (item instanceof SidebarItem.ConnectionItem ci
        && ci.connectionType() == ConnectionType.LOCAL_SHELL) {
    rawIcon = svgIcon(ICON_TERMINAL, 14, "sidebar-icon-terminal");
} else {
    rawIcon = svgIcon(ICON_SERVER, 14, "sidebar-icon-server");
}
Region icon = iconBadge(rawIcon, item instanceof SidebarItem.ConnectionItem ci ? ci.name() : "");
```

- [ ] **Step 2: Compile and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add ui/src/main/java/com/jlshell/ui/view/SidebarTreeView.java
git commit -m "style: add colored icon badges for connection items in sidebar"
```

---

### Task 4: Add SVG Icons to SFTP Toolbar Buttons

**Files:**
- Modify: `ui/src/main/java/com/jlshell/ui/view/SftpBrowserPane.java:112-126`

- [ ] **Step 1: Add SVG icon constants and update toolbar buttons**

Add these SVG path constants after the existing ones (after line 63):

```java
private static final String ICON_UPLOAD   = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12";
private static final String ICON_DOWNLOAD = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 8l5 5 5-5M12 13V1";
private static final String ICON_RENAME   = "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z";
private static final String ICON_DELETE   = "M3 6h18M8 6V4h8v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6";
private static final String ICON_MKDIR    = "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2zM12 11v6M9 14h6";
```

Replace `buildToolbar()` method (lines 112-126):

```java
private HBox buildToolbar() {
    Button upload   = toolButton(ICON_UPLOAD, i18nService.get("sftp.upload"));
    Button download = toolButton(ICON_DOWNLOAD, i18nService.get("sftp.download"));
    Button rename   = toolButton(ICON_RENAME, i18nService.get("sftp.rename"));
    Button delete   = toolButton(ICON_DELETE, i18nService.get("sftp.delete"));
    Button mkdir    = toolButton(ICON_MKDIR, i18nService.get("sftp.newFolder"));
    upload.setOnAction(e -> uploadSelected());
    download.setOnAction(e -> downloadSelected());
    rename.setOnAction(e -> renameSelectedRemoteFile());
    delete.setOnAction(e -> deleteSelectedRemoteFile());
    mkdir.setOnAction(e -> createRemoteDirectory());
    HBox bar = new HBox(4, upload, download, rename, delete, mkdir);
    bar.getStyleClass().add("toolbar-strip");
    return bar;
}
```

Add this helper method after `svgNavButton` (after line 765):

```java
private Button toolButton(String svgPath, String text) {
    Region icon = svgIcon(svgPath, 13);
    Button btn = new Button(text, icon);
    btn.getStyleClass().add("tool-button");
    btn.setTooltip(new javafx.scene.control.Tooltip(text));
    btn.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
    btn.setGraphicTextGap(5);
    return btn;
}
```

- [ ] **Step 2: Compile and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add ui/src/main/java/com/jlshell/ui/view/SftpBrowserPane.java
git commit -m "style: add SVG icons to SFTP toolbar buttons"
```

---

### Task 5: Add SVG Icons to Terminal Toolbar

**Files:**
- Modify: `ui/src/main/java/com/jlshell/ui/view/TerminalWorkspaceView.java:100-120`

- [ ] **Step 1: Replace Unicode icon text with SVG icons in terminal toolbar**

Add SVG path constants at the top of the class (after the field declarations):

```java
private static final String ICON_VSPLIT  = "M8 3v18M3 8h18";
private static final String ICON_HSPLIT  = "M3 8h18M8 3v18";
private static final String ICON_RESET   = "M4 4h16v16H4zM8 12h8";
private static final String ICON_FONT    = "m4 20 1.5-5h9L16 20M7.5 10 9 5h2l1.5 5";
```

Replace `buildToolbar()` and `iconBtn()` methods:

```java
private HBox buildToolbar() {
    Button verticalSplit   = svgToolbarBtn(ICON_VSPLIT, i18nService.get("terminal.splitVertical"),   () -> split(Orientation.VERTICAL));
    Button horizontalSplit = svgToolbarBtn(ICON_HSPLIT, i18nService.get("terminal.splitHorizontal"), () -> split(Orientation.HORIZONTAL));
    Button resetLayout     = svgToolbarBtn(ICON_RESET, i18nService.get("terminal.resetLayout"),      this::resetLayout);
    Button fontSettings    = svgToolbarBtn(ICON_FONT, i18nService.get("terminal.fontSettings"),      this::openFontSettings);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox bar = new HBox(4, verticalSplit, horizontalSplit, resetLayout, spacer, fontSettings);
    bar.getStyleClass().add("toolbar-strip");
    return bar;
}

private Button svgToolbarBtn(String svgPath, String tooltip, Runnable action) {
    Region icon = new Region();
    icon.setStyle(String.format(
            "-fx-min-width:14px;-fx-min-height:14px;-fx-max-width:14px;-fx-max-height:14px;" +
            "-fx-pref-width:14px;-fx-pref-height:14px;" +
            "-fx-shape:\"%s\";-fx-scale-shape:true;", svgPath));
    icon.getStyleClass().add("action-bar-icon");
    Button button = new Button();
    button.setGraphic(icon);
    button.setTooltip(new Tooltip(tooltip));
    button.getStyleClass().add("icon-btn");
    button.setOnAction(e -> action.run());
    return button;
}
```

Add `import javafx.scene.control.Tooltip;` and `import javafx.scene.layout.Region;` to imports if not already present.

- [ ] **Step 2: Compile and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add ui/src/main/java/com/jlshell/ui/view/TerminalWorkspaceView.java
git commit -m "style: replace Unicode icons with SVG icons in terminal toolbar"
```

---

### Task 6: Add Status Bar Connection Indicator

**Files:**
- Modify: `ui/src/main/java/com/jlshell/ui/view/MainWindow.java:328-333`

- [ ] **Step 1: Replace status bar with indicator dot + text**

Replace `buildStatusBar()` method (lines 328-333):

```java
private HBox buildStatusBar() {
    Region dot = new Region();
    dot.getStyleClass().addAll("status-indicator", "status-indicator-disconnected");
    Label statusLabel = new Label();
    statusLabel.textProperty().bind(viewModel.statusMessageProperty());
    statusLabel.getStyleClass().add("status-bar");
    HBox bar = new HBox(8, dot, statusLabel);
    bar.getStyleClass().add("status-bar");
    return bar;
}
```

Add `import javafx.scene.layout.HBox;` to imports if not already present.

Update the `openWorkspace` method to change the indicator to connected when a session opens. After line 588 (`workspaceTabs.getSelectionModel().select(tab);`), add:

```java
dot.getStyleClass().removeAll("status-indicator-disconnected");
dot.getStyleClass().add("status-indicator-connected");
```

To make `dot` accessible, promote it to a field. Add after line 79:

```java
private final Region statusDot = new Region();
```

Then in `buildStatusBar()`, use `statusDot` instead of the local `dot`.

In `openWorkspace`, after `workspaceTabs.getSelectionModel().select(tab);`:

```java
statusDot.getStyleClass().removeAll("status-indicator-disconnected");
statusDot.getStyleClass().add("status-indicator-connected");
```

In the tab close handler (inside `tab.setOnCloseRequest`), after removing the tab:

```java
if (workspaceTabs.getTabs().isEmpty()) {
    statusDot.getStyleClass().removeAll("status-indicator-connected");
    statusDot.getStyleClass().add("status-indicator-disconnected");
}
```

- [ ] **Step 2: Compile and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add ui/src/main/java/com/jlshell/ui/view/MainWindow.java
git commit -m "style: add connection status indicator dot to status bar"
```

---

### Task 7: Final Verification

- [ ] **Step 1: Full compile**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run application and visually verify**

Run: `mvn install -DskipTests -q && mvn javafx:run -pl app`

Verify:
- Dark theme loads with new color palette
- Light theme loads with new color palette (switch via View menu)
- Sidebar connection icons have colored backgrounds
- SFTP toolbar buttons have icons
- Terminal toolbar has SVG icons
- Status bar has connection indicator dot
- Selected items have left accent border
- Buttons have proper hover/pressed states
- Tabs have rounded top corners and accent underline

- [ ] **Step 3: Final commit if any fixes needed**

```bash
git add -A
git commit -m "style: final UI modernization fixes"
```

# 连接列表层级恢复

## 问题

连接列表初始化时，JavaFX `TreeItem.buildEventDispatchChain` 会递归访问父节点。此前直接按保存的 `parentId` 创建节点关系，文件夹指向自身或互相引用时会形成环，触发 `StackOverflowError`。Swing 工作台仍依赖 JavaFX 连接模型初始化，因此最终表现可能是原生连接列表没有显示；Swing 自己的建树也需要防护。

拖拽层曾将循环检查交给服务，但 `moveFolderToParent` 原本直接执行更新，并没有对应校验。日志中的父链栈溢出可用自引用和环形文件夹夹具稳定复现；没有读取或修改用户实际数据库，不能仅凭堆栈确认具体哪条记录异常。

## 修复

- JavaFX 和 Swing 共用纯数据 `FolderHierarchy`，先处理父级关系，再创建 UI 节点。
- 自引用、缺失父目录、跨项目父目录提升到显示根层；多节点环仅切断一条必要的显示边，其余关联继续保留。
- 使用迭代遍历处理层级；显示深度达到 64 时拆成新的根分支，防止极深的导入数据再次压垮 UI 递归。
- 保留文件夹 ID、名称、项目、排序及所有连接的文件夹归属；显示恢复不写数据库、不删除文件夹或连接。
- JavaFX 文件夹深度依据完整父链计算，不再取决于输入列表的排列顺序。
- 两套拖拽界面拒绝将文件夹或多选中的祖先放入自身/子孙；服务层在同一事务内再检查真实持久化父链，拒绝自身、子孙、循环链、缺失父级和跨项目父级。
- 移回顶层仍可用于修复既有异常关系。服务端不会因为显示层已经断环就信任非法移动。
- 删除确认的连接名遍历和文件夹删除改为有访问集合的迭代处理，避免对已有环无限递归。仅在用户主动删除时解开待删除文件夹间引用，清空其连接的文件夹归属，保留连接记录及其他文件夹。
- 遇到显示恢复时记录文件夹总数和调整父级数量，不打印连接地址、凭据或名称。

## 验证

1. 修复前，用真实 JavaFX `SidebarTreeView.populate` 加载自引用/两节点环夹具，稳定出现同样的 `TreeItem.buildEventDispatchChain` 栈溢出。
2. 修复后，同一夹具正常加载、搜索、展开/折叠和刷新，所有连接和文件夹各出现一次。
3. 真实 Swing 工作台验证异常层级显示、活动连接定位、搜索防抖及清空搜索后的恢复。
4. 纯数据测试验证乱序层级、5000 层输入、缺失父级、跨项目父级和多选拖拽；另以真实 JavaFX 验证 2000 层输入的加载及搜索。
5. 真实 SQLite 开启外键，验证非法移动和保存回滚、移回顶层恢复，以及环形文件夹删除后连接仍保留。

```bash
mvn -pl ui -am test \
  -Dtest=FolderHierarchyTest,ConnectionFolderHierarchyTest,SidebarHierarchyUiTest,WindowsWorkbenchPolishTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Djlshell.ui.smoke=true
mvn test
```

界面测试在本机 macOS 的真实 JavaFX/EDT 上运行，Windows 原生窗口尚需相应平台复核。无显示器的常规测试默认跳过真实窗口测试。本修复不修改已发布版本或自动发布新的安装包。

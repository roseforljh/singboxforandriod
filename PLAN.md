# SingBox for Android - UI/UX Design & Implementation Plan

## 1. Design Philosophy (设计理念)
*   **Style**: Modern Minimalist (现代极简)
*   **Color Palette**: Monochrome with Grayscale (黑白灰阶)
    *   **Primary**: #000000 (纯黑) - 用于主要按钮、强调文字、选中状态。
    *   **Background**: #FFFFFF (纯白) - 应用背景。
    *   **Surface**: #F8F9FA (极浅灰) - 卡片背景、次级区域。
    *   **Surface Variant**: #F0F0F0 (浅灰) - 边框、分割线。
    *   **Text Primary**: #1A1A1A (深灰黑) - 主要内容，避免纯黑刺眼。
    *   **Text Secondary**: #808080 (中灰) - 辅助信息、未选中状态。
*   **Typography**: System Default Sans-serif (Roboto/Product Sans 风格)，强调字重对比（Bold vs Light）。
*   **Motion**: Fluid, Spring-based animations (流体、弹性动画)。拒绝生硬的线性过渡。

## 2. Project Structure (项目结构)
*   **Language**: Kotlin
*   **UI Framework**: Jetpack Compose (Material3)
*   **Navigation**: Jetpack Navigation Compose
*   **Architecture**: MVVM (Model-View-ViewModel) - 即使是 UI 演示，也保持数据与视图分离，方便未来接入 Core。

```text
com.kunk.singbox
├── MainActivity.kt          // 入口
├── ui
│   ├── theme                // 设计系统
│   │   ├── Color.kt         // 颜色定义
│   │   ├── Theme.kt         // Material3 主题配置
│   │   └── Type.kt          // 字体样式
│   ├── components           // 通用组件
│   │   ├── AppNavBar.kt     // 底部导航栏
│   │   ├── BigToggle.kt     // 首页大开关 (核心交互)
│   │   └── InfoCard.kt      // 信息卡片
│   ├── navigation           // 路由配置
│   │   └── AppNavigation.kt
│   └── screens              // 页面
│       ├── DashboardScreen.kt
│       ├── ProfilesScreen.kt
│       └── SettingsScreen.kt
```

## 3. Screen Specifications (页面规范)

### A. Dashboard (首页)
*   **Layout**: 居中布局。
*   **Core Element**: 屏幕中央一个巨大的圆形或圆角矩形“启动/停止”按钮。
    *   *Animation*: 点击时有缩放反馈；启动状态下有呼吸灯效果或波纹扩散效果。
*   **Status Indicators**: 按钮下方显示简单的状态信息（如：Connected / Disconnected, Ping: 20ms）。
*   **Current Profile**: 顶部显示当前选中的节点名称，点击可快速切换。

### B. Profiles (节点列表)
*   **Layout**: 垂直列表。
*   **Item Style**: 卡片式设计，纯白背景 + 极浅灰边框。
*   **Interaction**:
    *   点击选中。
    *   长按触发编辑/删除菜单（底部弹窗 BottomSheet）。
    *   列表项进入时带有交错的淡入上移动画 (Staggered Fade-in Up)。

### C. Settings (设置)
*   **Layout**: 分组列表。
*   **Style**: 极简的开关 (Switch) 和 箭头 (Chevron)。
*   **Content**: 模拟常用设置项（如：分流模式、自动更新、主题设置等）。

## 4. Implementation Steps (实施步骤)
1.  **Project Setup**: 建立 Gradle 构建脚本和基础文件结构。
2.  **Theming**: 实现 `ui/theme`，定义黑白灰配色。
3.  **Navigation**: 搭建底部导航栏框架。
4.  **Dashboard**: 重点打磨“大开关”的动画手感。
5.  **Profiles**: 实现列表渲染和交互动画。
6.  **Settings**: 填充设置页面内容。
7.  **Polish**: 全局细节调整，确保转场流畅。
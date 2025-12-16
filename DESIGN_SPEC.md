# SingBox Android - OLED Hyper-Minimalist Design Spec

## 1. Design Tokens (Compose Mapping)

### Colors (Monochromatic)
*   **Background**: `Color(0xFF000000)` (Pure Black)
*   **Surface (Cards/Lists)**: `Color(0xFF121212)` (Neutral 900 equivalent) or `Color(0xFF0A0A0A)` (Neutral 950)
*   **Borders**: `Color(0xFF262626)` (Neutral 800 equivalent) - Thin, 1.dp
*   **Text Primary**: `Color(0xFFFFFFFF)` (White) - Active/Headings
*   **Text Secondary**: `Color(0xFF737373)` (Neutral 400/500) - Labels/Inactive
*   **Accents**: NO COLORS. Active state uses `Color.White` text on `Color.Black` bg or vice versa.
*   **Destructive**: `Color(0xFFEF4444)` (Red 500) - Sparingly used.

### Typography
*   **Default**: System Sans-serif
*   **Monospace**: System Monospace (for IPs, Ping, Version, Latency)

### Shapes
*   **Cards/Surfaces**: `RoundedCornerShape(16.dp)` or `24.dp`
*   **Buttons**: `CircleShape` (Pill/Capsule)

### Interaction
*   **Feedback**: Ripple effect (white with low opacity), Scale press effect (0.95f)
*   **Modals**: Background `Color.Black.copy(alpha = 0.8f)` with `Blur` (if supported)

## 2. Information Architecture

### Navigation (Bottom Bar)
1.  **Dashboard (首页)**: Connection, Quick Stats, Shortcuts
2.  **Nodes (节点)**: Proxy List, Groups, Latency Test
3.  **Profiles (配置)**: Subscription/File Management, Add Wizard
4.  **Settings (设置)**: Routing, DNS, TUN, Logs, Diagnostics

### Key Screens
*   **Dashboard**: Big Toggle, Status Chips, Speed Graph (Mock)
*   **Node List**: Search, Filter, Group Tabs, Node Cards
*   **Profile List**: Profile Cards, Add FAB
*   **Settings List**: Categorized List

## 3. UI States & Mock Data
*   **Connection**: Idle -> Connecting -> Connected -> Disconnecting
*   **Data Models**: `ProfileUi`, `NodeUi`, `RuleSetUi`
*   **Mock Repo**: Simulate latency tests, connection delays, log generation.

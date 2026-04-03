# SmartHome App - Claude Development Guide

## Project Overview

Ứng dụng SmartHome mobile xây dựng trên nền tảng **ThingsBoard IoT Platform**, sử dụng Flutter. Mục tiêu tạo trải nghiệm smarthome tương tự Tuya/Aqara/Xiaomi Home nhưng tích hợp sâu với ThingsBoard backend.

**Tech Stack:**
- Flutter 3.0+ / Dart 3.7+
- ThingsBoard Client SDK v4.1.0
- GoRouter v17 (routing)
- Hooks Riverpod v2.6 (state management)
- Hive (local cache)
- Firebase Messaging (push notifications)

---

## App Architecture: 3-Tab Navigation

```
BottomNavigationBar
├── Tab 1: Nhà (Home)      → /smarthome/home
├── Tab 2: Smart           → /smarthome/smart
└── Tab 3: Tôi (Profile)   → /smarthome/profile
```

Thay thế tab navigation hiện tại (dashboard-centric) bằng smarthome-centric layout cho `Authority.CUSTOMER_USER`.

---

## Tab 1: Nhà (Home Tab)

### Concept (tham khảo Tuya/Aqara/Xiaomi)
- Header: Tên nhà + dropdown chọn nhà (nếu có nhiều nhà) + thời tiết/nhiệt độ ngoài trời
- Tổng quan nhanh: số thiết bị online/offline, tổng năng lượng tiêu thụ, nhiệt độ trung bình
- Danh sách phòng dạng horizontal scroll (tab hoặc chips)
  - Tab đầu tiên: "Tất cả" — hiển thị tất cả thiết bị
  - Mỗi room chip hiện badge: số thiết bị đang ON hoặc cảnh báo nếu sensor bất thường
- Toggle xem: "Theo phòng" / "Theo loại thiết bị" (Xiaomi style)
- Mỗi phòng hiển thị:
  - **Kịch bản hay dùng** (Quick Scenes): horizontal scroll cards
  - **Thiết bị** (Devices): grid 2 cột, mỗi ô là DeviceCard
- Thiết bị mới thêm chưa gán phòng: hiển thị riêng ở cuối danh sách

### ThingsBoard Mapping
```
Nhà (Home)        → ThingsBoard Asset (type: "smarthome_home")
Phòng (Room)      → ThingsBoard Asset (type: "smarthome_room"), relation to home
Thiết bị (Device) → ThingsBoard Device, relation to room
Kịch bản (Scene)  → Server Attribute "scenes" trên Home Asset
```

### DeviceCard Widget
```dart
// Mỗi thiết bị hiển thị:
- Icon (SVG, tùy loại thiết bị — mapping từ device profile/type)
- Tên thiết bị
- Trạng thái (on/off, nhiệt độ, độ ẩm,...)
- Toggle nhanh (bật/tắt) — tap
- Long-press → Quick Actions menu (rename, move room, remove, device info)
- Background: sáng khi ON, tối khi OFF
- Offline indicator + "Cập nhật lần cuối: X phút trước"
```

### Data Flow
```
HomeTab
  → fetch Assets (type=smarthome_room, relation=home_asset_id)  [TB API: findByQuery]
  → per room: fetch Devices (relation=room_asset_id)             [TB API: findByQuery]
  → per device: subscribe telemetry/attributes                   [TB WebSocket]
  → QuickScenes: fetch server attribute "scenes" on Home Asset   [TB API: getAttributes]
  → Unassigned devices: fetch devices without room relation      [TB API: findByQuery]
```

### Key Files (to create)
```
lib/modules/smarthome/
├── home/
│   ├── presentation/
│   │   ├── home_tab.dart              # Main tab widget
│   │   ├── home_header.dart           # Tên nhà + thời tiết + tổng quan
│   │   ├── room_selector.dart         # Horizontal room tabs with badges
│   │   ├── view_mode_toggle.dart      # Theo phòng / Theo loại
│   │   ├── room_content.dart          # Scenes + devices grid
│   │   ├── device_card.dart           # 2-column device tile
│   │   ├── device_quick_actions.dart  # Long-press menu
│   │   └── quick_scene_card.dart      # Scene shortcut card
│   ├── domain/
│   │   ├── entities/room.dart
│   │   ├── entities/smarthome_device.dart
│   │   └── entities/scene.dart
│   └── providers/
│       ├── home_provider.dart         # Riverpod providers
│       ├── room_provider.dart         # Room list + selected room
│       └── device_state_provider.dart # Real-time device state via WebSocket
```

---

## Tab 2: Smart (Automation Tab)

### Concept (tham khảo Tuya Smart/Xiaomi Automation)
Hai section chính:
1. **Chạm để chạy** (Tap to Run / Manual Scenes)
2. **Tự động hóa** (Automations)

### Tap to Run Scenes
- Grid 2 cột các scene có thể trigger thủ công
- Mỗi card: icon (customizable) + màu nền (customizable) + tên + nút "Chạy"
- ThingsBoard: gọi RPC hoặc cập nhật shared attribute để trigger Rule Chain / Gateway

### Automation (If-Then Rules)
Cấu trúc rule:
```
Automation {
  id: string (uuid)
  name: string
  icon: string
  color: string
  enabled: bool
  condition_match: "all" | "any"       // AND hoặc OR giữa các conditions
  
  conditions: [                        // NẾU (If)
    {
      type: "device_state"
      device_id: string
      gateway_id: string?              // null nếu device không thuộc gateway
      attribute: string                // vd: "temperature", "motion", "switch"
      operator: ">"|"<"|"=="|"!="|">="|"<="
      value: dynamic
    },
    {
      type: "time_schedule"
      cron: string                     // cron expression
    },
    {
      type: "time_range"
      from: "HH:mm"
      to: "HH:mm"
    },
    {
      type: "day_of_week"
      days: [1,2,3,4,5]               // 1=Mon, 7=Sun
    },
    {
      type: "sunrise_sunset"
      event: "sunrise"|"sunset"
      offset_minutes: int              // +30 = 30 phút sau sunrise
      latitude: double
      longitude: double
    },
    {
      type: "geofencing"
      event: "enter"|"leave"
      latitude: double
      longitude: double
      radius_meters: int
    },
    {
      type: "device_offline"
      device_id: string
      duration_seconds: int            // offline bao lâu thì trigger
    },
    {
      type: "weather"
      attribute: "temperature"|"humidity"|"condition"
      operator: string
      value: dynamic
    }
  ]
  
  actions: [                           // THÌ (Then)
    {
      type: "device_command"
      device_id: string
      gateway_id: string?
      command: string                  // RPC method
      params: Map<String, dynamic>
    },
    {
      type: "delay"
      seconds: int                     // Chờ X giây trước action tiếp theo
    },
    {
      type: "run_scene"
      scene_id: string
    },
    {
      type: "send_notification"
      message: string
      target: "all"|"owner"            // Gửi cho ai
    },
    {
      type: "enable_automation"
      automation_id: string
      enabled: bool                    // Bật/tắt automation khác
    }
  ]
  
  extra: {
    trigger_once: bool                 // Xảy ra 1 lần hay nhiều lần
    effective_period: {                // Thời gian hiệu lực
      type: "always"|"custom"
      from: "HH:mm"
      to: "HH:mm"
      days: [int]
    }
  }
}
```

### Automation Execution Architecture

**Nguyên tắc quan trọng: Gateway-local execution**

Khi TẤT CẢ devices trong 1 automation rule (cả conditions lẫn actions) đều thuộc cùng 1 Gateway, rule đó được lưu trữ và thực thi trực tiếp trên Gateway. Điều này:
- Giảm latency (không cần round-trip qua server)
- Tiết kiệm tài nguyên hệ thống (server không cần xử lý)
- Hoạt động kể cả khi mất kết nối internet (local execution)

**Phân loại automation theo nơi thực thi:**

```
┌─────────────────────────────────────────────────────────┐
│              App tạo/sửa automation rule                │
│                        │                                │
│              Phân tích devices trong rule                │
│                  ┌─────┴──────┐                         │
│                  │            │                          │
│         Tất cả thuộc    Có devices thuộc                │
│         cùng 1 GW      nhiều GW hoặc                   │
│                  │      cloud devices                   │
│                  ▼            ▼                          │
│         ┌──────────┐  ┌──────────────┐                  │
│         │ GATEWAY  │  │ THINGSBOARD  │                  │
│         │  LOCAL   │  │   SERVER     │                  │
│         └──────────┘  └──────────────┘                  │
│                                                         │
│  Lưu: RPC gửi rule   Lưu: Server Attribute             │
│  JSON xuống Gateway   "automations" trên Home Asset     │
│                                                         │
│  Thực thi: Gateway    Thực thi: Rule Chain              │
│  engine chạy local    "SmartHome Automation Engine"     │
│                                                         │
│  Ưu điểm:            Ưu điểm:                          │
│  - Latency thấp       - Cross-gateway interaction       │
│  - Offline capable     - Cloud device support           │
│  - Tiết kiệm server   - Complex logic support          │
└─────────────────────────────────────────────────────────┘
```

**Gateway-local execution flow:**
```
1. App tạo automation → detect tất cả devices cùng 1 gateway
2. App gọi RPC tới Gateway: method="set_automation", params={rule_json}
3. Gateway lưu rule vào local storage
4. Gateway engine: listen sub-device events → evaluate conditions → execute actions
5. Gateway report automation status lên ThingsBoard (telemetry)
6. App đồng bộ: lưu bản copy rule lên Server Attribute (backup + hiển thị)
```

**Server-side execution flow (cross-gateway / cloud):**
```
1. App tạo automation → detect devices thuộc nhiều gateway hoặc cloud
2. App lưu rule vào Server Attribute "automations" trên Home Asset
3. ThingsBoard Rule Chain "SmartHome Automation Engine":
   a. Node: Listen telemetry/attribute update trên mọi device
   b. Node: Script đọc automation rules từ Home Asset attribute
   c. Node: Evaluate conditions (match "all" hoặc "any")
   d. Node: Process delay actions (queued)
   e. Node: Execute actions → RPC to devices / Gateway
4. Rule Chain ghi log kết quả vào telemetry
```

**Sync & Conflict Resolution:**
```
- Mỗi rule có field: execution_target: "gateway:{id}" | "server"
- Khi edit rule trên app → re-evaluate target → migrate nếu cần
- Gateway periodically sync rule status lên server
- Nếu Gateway offline: server-side rules vẫn cố gắng gửi (queue + retry)
```

### Automation Log / History
- Lịch sử automation đã chạy: thời gian, rule name, kết quả (thành công/thất bại)
- Gateway-local: Gateway gửi execution log lên ThingsBoard telemetry
- Server-side: Rule Chain ghi trực tiếp
- ThingsBoard mapping: telemetry key "automation_log" trên Home Asset

### Create/Edit Automation Flow (UI)
```
AutomationEditPage
├── Step 1: Basic Info (name, icon, color)
├── Step 2: NẾU (If Conditions)
│   ├── Condition match: AND / OR toggle
│   ├── AddConditionSheet
│   │   ├── Trạng thái thiết bị → Chọn thiết bị → Chọn thuộc tính → Operator → Giá trị
│   │   ├── Hẹn giờ (cron / time range)
│   │   ├── Ngày trong tuần
│   │   ├── Mặt trời mọc/lặn ± offset
│   │   ├── Vị trí (geofencing)
│   │   ├── Thiết bị offline
│   │   └── Thời tiết
│   └── Condition list (drag to reorder)
├── Step 3: THÌ (Then Actions)
│   ├── AddActionSheet
│   │   ├── Điều khiển thiết bị → Chọn lệnh → Nhập params
│   │   ├── Chờ (delay X giây/phút)
│   │   ├── Chạy kịch bản khác
│   │   ├── Gửi thông báo
│   │   └── Bật/tắt automation khác
│   └── Action list (drag to reorder, delay giữa các action)
├── Step 4: Điều kiện phụ (Extra)
│   ├── Trigger 1 lần / nhiều lần
│   ├── Thời gian hiệu lực
│   └── Ngày trong tuần
└── Preview: Hiển thị nơi thực thi (Gateway local / Server)
    └── Badge: "⚡ Chạy trên Gateway" hoặc "☁️ Chạy trên Server"
```

### Key Files (to create)
```
lib/modules/smarthome/
├── smart/
│   ├── presentation/
│   │   ├── smart_tab.dart
│   │   ├── tap_to_run_section.dart
│   │   ├── automation_section.dart
│   │   ├── automation_card.dart
│   │   ├── automation_edit_page.dart
│   │   ├── automation_preview.dart     # Preview nơi thực thi
│   │   ├── automation_log_page.dart    # Lịch sử thực thi
│   │   ├── condition_builder.dart
│   │   ├── condition_type_picker.dart
│   │   ├── action_builder.dart
│   │   ├── action_type_picker.dart
│   │   └── scene_icon_picker.dart      # Chọn icon + màu
│   ├── domain/
│   │   ├── entities/automation_rule.dart
│   │   ├── entities/condition.dart
│   │   ├── entities/action.dart
│   │   ├── entities/automation_log.dart
│   │   └── entities/execution_target.dart  # gateway vs server
│   └── providers/
│       ├── automation_provider.dart
│       ├── automation_log_provider.dart
│       └── execution_target_resolver.dart  # Phân tích nơi thực thi
```

---

## Tab 3: Tôi (Profile/Settings Tab)

### Concept (tham khảo Aqara/Tuya)
```
Profile Header
  - Avatar + Tên người dùng + email

Quản lý nhà (Home Management)
  - Danh sách nhà (nếu có nhiều nhà)
  - Thêm nhà mới
  - Quản lý phòng (CRUD + sắp xếp thứ tự)
  - Quản lý thành viên
    - Roles: Owner (chủ nhà), Admin (quản trị), Member (thành viên)
    - Owner: toàn quyền
    - Admin: điều khiển + tạo automation, không xóa nhà
    - Member: chỉ điều khiển thiết bị + chạy scene

Thiết bị & Kết nối
  - Thêm thiết bị mới (provisioning)
  - Thiết bị offline (danh sách)
  - Gateway status (online/offline, firmware version)

Thông báo
  - Lịch sử thông báo (alarm history)
  - Cấu hình: loại thông báo nào nhận push / tắt

Nhật ký hoạt động (Activity Log)
  - Ai đã bật/tắt thiết bị nào, lúc nào
  - ThingsBoard audit log API

Cài đặt (Settings)
  - Ngôn ngữ
  - Giao diện (Dark/Light mode)
  - Đơn vị đo (°C/°F)

Thông tin & Hỗ trợ
  - Phiên bản ứng dụng
  - Hướng dẫn sử dụng
  - Liên hệ hỗ trợ

Đăng xuất
```

### ThingsBoard Mapping
```
Quản lý nhà    → CRUD Assets (type: "smarthome_home")
Quản lý phòng  → CRUD Assets (type: "smarthome_room"), manage relations
Quản lý thành viên → ThingsBoard Customer Users + server attribute "role" trên User
Nhật ký        → ThingsBoard Audit Log API
Thông báo      → ThingsBoard Alarm API + Firebase push
```

### Key Files (to create)
```
lib/modules/smarthome/
├── profile/
│   ├── presentation/
│   │   ├── profile_tab.dart
│   │   ├── home_management_page.dart
│   │   ├── room_management_page.dart
│   │   ├── member_management_page.dart
│   │   ├── notification_settings_page.dart
│   │   ├── notification_history_page.dart
│   │   ├── activity_log_page.dart
│   │   └── settings_page.dart
│   └── providers/
│       ├── profile_provider.dart
│       ├── member_provider.dart
│       └── activity_log_provider.dart
```

---

## Device Detail Pages

Mỗi loại thiết bị có UI riêng khi tap vào DeviceCard. Đây là nơi user tương tác nhiều nhất.

### Device Detail Layouts (per type)
```
Light (Đèn)
  ├── Toggle ON/OFF lớn
  ├── Slider: Brightness (0-100%)
  ├── Slider: Color Temperature (warm ↔ cool)
  ├── Color Wheel (nếu RGB light)
  ├── Preset modes: Reading, Relax, Night, Party
  └── Schedule: Hẹn giờ bật/tắt

Air Conditioner (Điều hòa)
  ├── Toggle ON/OFF
  ├── Temperature: ±1° (circular slider hoặc +/- buttons)
  ├── Mode: Cool / Heat / Auto / Dry / Fan
  ├── Fan Speed: Auto / Low / Medium / High
  ├── Swing: ON/OFF
  ├── Current room temp + humidity (từ telemetry)
  └── Schedule: Hẹn giờ

Curtain / Rèm
  ├── Slider: Position (0-100%)
  ├── Buttons: Mở / Dừng / Đóng
  └── Schedule

Smart Plug (Ổ cắm thông minh)
  ├── Toggle ON/OFF
  ├── Power consumption chart (24h/7d/30d)
  ├── Countdown timer (tắt sau X phút)
  └── Energy statistics

Door/Window Sensor (Cảm biến cửa)
  ├── Status: Mở / Đóng (icon lớn)
  ├── History chart: timeline mở/đóng
  ├── Battery level
  └── Alert settings

Motion Sensor (Cảm biến chuyển động)
  ├── Status: Có chuyển động / Không (icon lớn)
  ├── History chart: timeline phát hiện
  ├── Sensitivity setting (nếu có)
  ├── Battery level
  └── Alert settings

Temperature/Humidity Sensor
  ├── Current values (large display)
  ├── History chart: 24h/7d/30d
  ├── Min/Max thresholds (cảnh báo)
  └── Battery level

Camera
  ├── Live stream view (RTSP/HLS)
  ├── PTZ controls (nếu có)
  ├── Record / Snapshot
  └── Motion detection settings
```

### Common Device Detail Elements
```
Mỗi device detail page đều có:
  - Header: Tên thiết bị + icon + online/offline status
  - Tab "Điều khiển" (Control) — UI tùy loại ở trên
  - Tab "Lịch sử" (History) — telemetry charts
  - Tab "Cài đặt" (Settings):
    - Đổi tên
    - Đổi icon
    - Chuyển phòng
    - Thông tin thiết bị (firmware, model, gateway)
    - Xóa thiết bị
  - FAB: "Tạo kịch bản với thiết bị này" → mở automation builder pre-filled
```

### Key Files (to create)
```
lib/modules/smarthome/
├── device_detail/
│   ├── presentation/
│   │   ├── device_detail_page.dart       # Router → chọn UI theo type
│   │   ├── device_history_tab.dart       # Telemetry charts
│   │   ├── device_settings_tab.dart      # Common settings
│   │   ├── types/
│   │   │   ├── light_control.dart
│   │   │   ├── ac_control.dart
│   │   │   ├── curtain_control.dart
│   │   │   ├── smart_plug_control.dart
│   │   │   ├── door_sensor_view.dart
│   │   │   ├── motion_sensor_view.dart
│   │   │   ├── temp_humidity_view.dart
│   │   │   └── camera_view.dart
│   │   └── widgets/
│   │       ├── circular_slider.dart      # Cho temperature
│   │       ├── brightness_slider.dart
│   │       ├── color_wheel.dart
│   │       ├── telemetry_chart.dart
│   │       └── schedule_picker.dart
│   └── providers/
│       ├── device_detail_provider.dart
│       └── device_telemetry_provider.dart
```

---

## Device Provisioning Flow

### Thêm thiết bị qua Gateway
```
1. Tap "+" trên Home Tab hoặc "Thêm thiết bị" trong Profile Tab
2. Chọn Gateway (nếu có nhiều gateway)
3. Chọn loại thiết bị từ catalog (hiển thị icon + tên loại)
4. Hướng dẫn kết nối (animated step-by-step):
   - Zigbee: "Nhấn giữ nút pair trên thiết bị 5 giây..."
   - WiFi: "Kết nối WiFi của thiết bị..."
   - BLE: "Đưa thiết bị lại gần điện thoại..."
5. App gửi RPC tới Gateway: method="start_pairing", params={device_type}
6. Gateway scan + pair → report new device lên ThingsBoard
7. App detect thiết bị mới → Hiển thị form:
   - Đặt tên thiết bị
   - Chọn phòng (hoặc "Chưa gán phòng")
   - Chọn icon
8. App lưu relation (room CONTAINS device) + device attributes
```

### ThingsBoard Gateway Protocol
```
Gateway → ThingsBoard:
  - Connect device:  MQTT publish "v1/gateway/connect"     → {"device": "name"}
  - Telemetry:       MQTT publish "v1/gateway/telemetry"    → {"device": {"ts": ..., "values": {...}}}
  - Attributes:      MQTT publish "v1/gateway/attributes"   → {"device": {"key": "value"}}

ThingsBoard → Gateway:
  - RPC request:     MQTT subscribe "v1/gateway/rpc"        → {"device": "name", "data": {"method": "...", "params": {...}}}
  - Attribute update: MQTT subscribe "v1/gateway/attributes" → shared attribute changes
```

### Key Files (to create)
```
lib/modules/smarthome/
├── provisioning/
│   ├── presentation/
│   │   ├── add_device_page.dart
│   │   ├── gateway_selector.dart
│   │   ├── device_type_catalog.dart
│   │   ├── pairing_guide.dart          # Animated instructions
│   │   ├── pairing_progress.dart       # Scanning/pairing state
│   │   └── device_setup_form.dart      # Name, room, icon
│   └── providers/
│       └── provisioning_provider.dart
```

---

## ThingsBoard Data Model

### Asset Hierarchy
```
Customer
  └── Asset: "My Home" (type: "smarthome_home")
        ├── server_attributes: {
        │     "scenes": [...],
        │     "automations": [...],       // Server-side automations only
        │     "weather_location": { "lat": ..., "lon": ... }
        │   }
        ├── Asset: "Phòng khách" (type: "smarthome_room")
        │     ├── server_attributes: { "icon": "living_room", "order": 1 }
        │     ├── Device: "Đèn trần" (type: "light")
        │     └── Device: "Điều hòa" (type: "air_conditioner")
        ├── Asset: "Phòng ngủ" (type: "smarthome_room")
        │     └── Device: "Đèn ngủ" (type: "light")
        ├── Asset: "Nhà bếp" (type: "smarthome_room")
        └── Device: Gateway (type: "gateway")
              ├── server_attributes: {
              │     "local_automations": [...]   // Gateway-local automations
              │   }
              ├── Sub-device: "Đèn trần"        // Connected via Gateway
              ├── Sub-device: "Điều hòa"
              └── Sub-device: "Cảm biến cửa"
```

### Device Types & Attributes
```
light              → telemetry: {brightness, color_temp, color_rgb}
                     shared_attr: {switch, brightness_set, color_temp_set, color_set}
                     RPC: setValue, toggle

air_conditioner    → telemetry: {temperature, humidity, power_w}
                     shared_attr: {power, mode, target_temp, fan_speed, swing}
                     RPC: setMode, setTemp, toggle

smart_plug         → telemetry: {power_w, energy_kwh, voltage, current}
                     shared_attr: {switch, countdown_seconds}
                     RPC: toggle, setCountdown

curtain            → telemetry: {position}
                     shared_attr: {command, position_set}
                     RPC: open, close, stop, setPosition

door_sensor        → telemetry: {contact, battery}
                     client_attr: {last_open_time, last_close_time}

motion_sensor      → telemetry: {motion, illuminance, battery}
                     shared_attr: {sensitivity}

temp_humidity      → telemetry: {temperature, humidity, battery}
                     shared_attr: {temp_threshold_high, temp_threshold_low}

camera             → server_attr: {stream_url, rtsp_url}
                     shared_attr: {motion_detect_enabled}
                     RPC: ptz_control, snapshot

gateway            → telemetry: {cpu_usage, memory_usage, uptime, sub_device_count}
                     server_attr: {firmware_version, local_automations}
                     RPC: set_automation, remove_automation, start_pairing, reboot
```

### Relations
- `home CONTAINS room` (type: "Contains")
- `room CONTAINS device` (type: "Contains")
- Gateway → sub-devices: managed by ThingsBoard Gateway protocol (automatic)

---

## Smarthome Service Layer

### New Services to Create
```
lib/utils/services/smarthome/
├── smarthome_service.dart            # High-level smarthome operations
├── home_service.dart                 # Home CRUD + member management
├── room_service.dart                 # Room CRUD + device assignment
├── device_control_service.dart       # Send RPC commands to devices
├── telemetry_subscription.dart       # WebSocket real-time updates
├── automation_service.dart           # CRUD automation rules
├── automation_target_resolver.dart   # Determine gateway-local vs server
├── gateway_automation_service.dart   # RPC to set/remove automations on gateway
├── scene_service.dart                # CRUD scenes + execute
├── provisioning_service.dart         # Device pairing via gateway
└── activity_log_service.dart         # Audit log queries
```

### Key ThingsBoard APIs Used
```dart
// Asset management
tbClient.getAssetService().saveAsset(...)                  // Create/update asset
tbClient.getAssetService().deleteAsset(...)                // Delete asset
tbClient.getAssetService().findByQuery(...)                // Query assets

// Relation management
tbClient.getRelationService().saveRelation(...)            // Create relation
tbClient.getRelationService().deleteRelation(...)          // Delete relation
tbClient.getRelationService().findByFrom(...)              // Find children

// Device control
tbClient.getDeviceService().getDevice(...)                 // Device info
tbClient.getAttributeService().saveEntityAttributesV2(...) // Set attributes
// RPC: POST /api/rpc/twoway/{deviceId}                    // Send RPC command

// Real-time telemetry
tbClient.getTelemetryWebsocketService().subscribe(...)     // WebSocket sub

// Automation & scenes storage
tbClient.getAttributeService().getEntityAttributes(...)    // Load rules
tbClient.getAttributeService().saveEntityAttributesV2(...) // Save rules

// User management
tbClient.getUserService().getCustomerUsers(...)            // List members
tbClient.getUserService().saveUser(...)                    // Add member

// Audit log
tbClient.getAuditLogService().getAuditLogs(...)            // Activity history
```

---

## Real-time & Offline Strategy

### Online Mode
```
- WebSocket subscription cho tất cả devices trong nhà hiện tại
- Subscribe: LATEST_TELEMETRY + SHARED_SCOPE attributes
- Update UI realtime khi nhận data mới
- Heartbeat check: reconnect nếu mất kết nối
```

### Offline Mode
```
- Hive cache: lưu last-known state cho tất cả devices
- Hive cache: lưu room structure + home info
- Queue commands: nếu mất mạng, lưu pending commands
- Sync khi online: gửi queued commands, refresh all states
- UI indicators:
  - Banner: "Không có kết nối mạng"
  - Device card: "Offline" badge
  - Timestamp: "Cập nhật lần cuối: 5 phút trước"
  - Pending command: "Đang chờ gửi..."
```

---

## UI Design Guidelines

### Design Language (tham khảo Tuya Smart)
- **Color Scheme:** Hỗ trợ Dark (#1C1C1E bg) và Light mode, theo system setting
- **Device Card (ON):** Background màu chủ đạo (accent color nhạt), icon sáng
- **Device Card (OFF):** Background xám nhạt (#F5F5F5 light / #2C2C2E dark), icon mờ
- **Border Radius:** 16px cho cards, 12px cho buttons
- **Typography:**
  - Room name: 18sp Bold
  - Device name: 14sp Medium
  - Device status: 12sp Regular, màu secondary
- **Spacing:** 16px padding chuẩn, 12px gap giữa cards

### Device Card Layout
```
┌─────────────────────┐
│ [Icon]    [Toggle]  │
│                     │
│ Tên thiết bị        │
│ Trạng thái          │
└─────────────────────┘
Width: (screenWidth - 48) / 2
Height: ~110dp
Long-press: Quick Actions bottom sheet
```

### Room Selector
- Horizontal scroll với chips/tabs
- "Tất cả" là tab đầu tiên
- Selected: filled chip với màu primary
- Unselected: outlined chip
- Badge: số thiết bị ON (nhỏ, góc trên phải chip)

### Quick Scene Card
```
┌──────────────────┐
│ [Icon + Color]   │
│ Tên kịch bản     │
└──────────────────┘
Tap → execute scene
Horizontal scroll
```

### Automation Card
```
┌──────────────────────────────────┐
│ [Icon] Tên automation    [Toggle]│
│ If: Motion detected, After 22:00│
│ Then: Turn on light              │
│ ⚡ Gateway local                 │
└──────────────────────────────────┘
```

---

## Development Phases

### Phase 1: Foundation (Ưu tiên cao)
- [ ] Tạo SmartHome data model (entities, providers)
- [ ] SmartHome service layer (home/room/device queries)
- [ ] Tích hợp tab navigation mới vào navigation system
- [ ] Home Tab: Header + Room selector + Device grid
- [ ] DeviceCard widget với real-time state (WebSocket)

### Phase 2: Device Control & Detail
- [ ] Device detail page framework (router by device type)
- [ ] Light control UI
- [ ] AC control UI
- [ ] Curtain, smart plug, sensor UIs
- [ ] RPC command sending
- [ ] Telemetry history charts

### Phase 3: Smart Tab - Scenes & Automation
- [ ] Tap-to-run scene CRUD + execution
- [ ] Automation list view + toggle
- [ ] Automation create/edit flow (step-by-step)
- [ ] Condition builders (device state, time, geofencing, sunrise/sunset)
- [ ] Action builders (device command, delay, notification)
- [ ] Execution target resolver (gateway-local vs server)
- [ ] Gateway RPC: set/remove automation
- [ ] Automation execution log

### Phase 4: Profile Tab & Management
- [ ] Profile/settings page
- [ ] Home management (CRUD homes)
- [ ] Room management (CRUD rooms, reorder)
- [ ] Member management (invite, roles)
- [ ] Notification history + settings
- [ ] Activity log

### Phase 5: Device Provisioning
- [ ] Add device flow via Gateway
- [ ] Device type catalog
- [ ] Pairing guide (animated)
- [ ] Gateway RPC: start_pairing
- [ ] Device setup form (name, room, icon)

### Phase 6: Polish & Advanced
- [ ] Animations & transitions
- [ ] Offline support (Hive caching + command queue)
- [ ] Push notification for automation triggers
- [ ] Dark/Light theme
- [ ] Localization (Vietnamese, English)

---

## Coding Conventions

### File Naming
- Screens: `*_tab.dart`, `*_page.dart`
- Widgets: `*_card.dart`, `*_widget.dart`, `*_item.dart`
- Providers: `*_provider.dart` (Riverpod @riverpod annotation)
- Services: `*_service.dart`
- Entities: snake_case filename, PascalCase class

### State Management Pattern
```dart
// Sử dụng Riverpod với @riverpod annotation
@riverpod
Future<List<Room>> rooms(RoomsRef ref, String homeAssetId) async {
  final service = ref.watch(smarthomeServiceProvider);
  return service.getRooms(homeAssetId);
}

// Real-time state dùng StreamProvider
@riverpod
Stream<DeviceState> deviceState(DeviceStateRef ref, String deviceId) {
  final telemetry = ref.watch(telemetryServiceProvider);
  return telemetry.subscribeDevice(deviceId);
}
```

### Error Handling
- Dùng `AsyncValue` từ Riverpod cho loading/error/data states
- Hiển thị skeleton loading khi fetch data
- Retry button khi có lỗi kết nối

### ThingsBoard Client Access
```dart
// Lấy TB client trong provider/service
final tbClient = locator<ITbClientService>().tbClient;
```

---

## Gateway Integration Details

### Gateway → ThingsBoard (MQTT)
```
Connect device:     v1/gateway/connect        → {"device": "Light_01"}
Disconnect:         v1/gateway/disconnect      → {"device": "Light_01"}
Telemetry:          v1/gateway/telemetry       → {"Light_01": [{"ts": 123, "values": {"brightness": 80}}]}
Attributes:         v1/gateway/attributes      → {"Light_01": {"firmware": "1.0"}}
```

### ThingsBoard → Gateway (MQTT)
```
RPC request:        v1/gateway/rpc             → {"device": "Light_01", "data": {"id": 1, "method": "toggle", "params": {}}}
Attribute update:   v1/gateway/attributes/response
```

### Gateway Local Automation Engine
```
Gateway nhận automation rules qua RPC method "set_automation":
{
  "id": "uuid",
  "conditions": [...],
  "actions": [...],
  "condition_match": "all",
  "extra": {...}
}

Gateway thực thi:
1. Subscribe events từ tất cả sub-devices local
2. Khi event xảy ra → check conditions
3. Nếu match → execute actions (gửi command tới sub-device qua local protocol)
4. Report execution log lên ThingsBoard: telemetry {"automation_log": {...}}
```

App mobile tương tác với Gateway thông qua ThingsBoard API (không kết nối trực tiếp với Gateway).

---

## Key References

- ThingsBoard REST API: `{TB_HOST}/swagger-ui.html`
- ThingsBoard Gateway MQTT API: `{TB_HOST}/docs/reference/gateway-mqtt-api/`
- ThingsBoard Dart Client: `package:thingsboard_client`
- GoRouter docs: `go_router` package
- Riverpod docs: `hooks_riverpod` package
- Tuya Smart App: Tham khảo UX/flow, device card design, automation builder
- Aqara Home: Tham khảo device detail pages, scene system
- Xiaomi Home: Tham khảo room management, device grouping, automation UI

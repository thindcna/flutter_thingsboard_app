# ThingsBoard SmartHome Setup Guide

Hướng dẫn import và cấu hình ThingsBoard CE cho SmartHome app.

## Thứ tự cài đặt

### 1. Tạo Asset Profiles

Vào **Profiles → Asset profiles → "+"**:

1. **SmartHome Home**
   - Name: `SmartHome Home`
   - Default rule chain: `SmartHome Automation Engine` (set sau khi import rule chain)

2. **SmartHome Room**
   - Name: `SmartHome Room`

### 2. Tạo Device Profiles

Vào **Profiles → Device profiles → "+"**:

| Profile Name | Transport | Alarm rules |
|---|---|---|
| SmartHome Light | DEFAULT | Offline 5m |
| SmartHome Air Conditioner | DEFAULT | High temp, Offline 5m |
| SmartHome Smart Plug | DEFAULT | Power overload >2200W |
| SmartHome Curtain | DEFAULT | Offline 10m |
| SmartHome Door/Window Sensor | DEFAULT | Open >30m, Low battery <20% |
| SmartHome Motion Sensor | DEFAULT | Low battery <20% |
| SmartHome Temp/Humidity Sensor | DEFAULT | High/Low temp, High humidity, Low battery |
| SmartHome Camera | DEFAULT | Motion detected, Offline 3m (MAJOR) |
| SmartHome Gateway | MQTT | Offline 2m (CRITICAL), High CPU >90% |

**Quan trọng:** Gateway profile phải dùng transport type = MQTT và tick "Is gateway" checkbox.

### 3. Import Rule Chains

Vào **Rule chains → "+" → Import rule chain**:

#### 3.1. SmartHome Automation Engine (import trước)
- File: `rule_chains/smarthome_automation_engine.json`
- Đây là rule chain chính xử lý automation

#### 3.2. SmartHome Scheduler Heartbeat (import sau)
- File: `rule_chains/smarthome_scheduler_heartbeat.json`
- Sau khi import, mở rule chain này:
  - Click node cuối → sửa `targetRuleChainId` thành ID của "SmartHome Automation Engine"

### 4. Kết nối Rule Chain với Root Rule Chain

Mở **Root Rule Chain** → thêm **Rule Chain Node**:
- Chọn "SmartHome Automation Engine"
- Kết nối từ "Message Type Switch" node → "SmartHome Automation Engine" node
- Connection types: `Post telemetry`, `Post attributes`

Hoặc set "SmartHome Automation Engine" làm **Default Rule Chain** của Asset Profile "SmartHome Home".

### 5. Tạo Scheduler Device (cho time-based automations)

Nếu cần automation trigger theo thời gian (cron) mà không phụ thuộc telemetry:

1. Tạo device mới:
   - Name: `SmartHome Scheduler`
   - Device profile: Tạo profile đơn giản hoặc dùng DEFAULT
   - Type: `scheduler`

2. Gửi telemetry mỗi 60 giây:
   ```json
   {"heartbeat": true}
   ```
   Có thể dùng script/cron job:
   ```bash
   # Chạy mỗi phút
   * * * * * curl -X POST "http://TB_HOST/api/v1/DEVICE_TOKEN/telemetry" \
     -H "Content-Type: application/json" \
     -d '{"heartbeat": true}'
   ```

### 6. Tạo dữ liệu mẫu

#### 6.1. Tạo Customer
- Vào **Customers → "+"** → Tạo customer cho gia đình

#### 6.2. Tạo Home Asset
```
POST /api/asset
{
  "name": "Nhà tôi",
  "type": "smarthome_home",
  "customerId": { "id": "CUSTOMER_ID" }
}
```

Sau đó set server attributes:
```
POST /api/plugins/telemetry/{HOME_ASSET_ID}/SERVER_SCOPE
{
  "scenes": [],
  "automations": [],
  "weather_location": {"lat": 21.0285, "lon": 105.8542, "city": "Ha Noi"},
  "members": [{"user_id": "USER_ID", "role": "owner", "name": "Owner"}]
}
```

#### 6.3. Tạo Room Assets
```
POST /api/asset
{
  "name": "Phòng khách",
  "type": "smarthome_room",
  "customerId": { "id": "CUSTOMER_ID" }
}

# Set attributes
POST /api/plugins/telemetry/{ROOM_ASSET_ID}/SERVER_SCOPE
{"icon": "living_room", "order": 0}

# Tạo relation: Home CONTAINS Room
POST /api/relation
{
  "from": {"entityType": "ASSET", "id": "HOME_ASSET_ID"},
  "to": {"entityType": "ASSET", "id": "ROOM_ASSET_ID"},
  "type": "Contains"
}
```

#### 6.4. Tạo Gateway Device
```
POST /api/device
{
  "name": "Gateway Phòng khách",
  "type": "gateway",
  "customerId": { "id": "CUSTOMER_ID" },
  "deviceProfileId": { "id": "GATEWAY_PROFILE_ID" }
}
```
Tick **"Is gateway"** trong device credentials.

#### 6.5. Tạo Sub-devices (ví dụ Light)
Sub-devices được Gateway tự tạo qua MQTT `v1/gateway/connect`.
Hoặc tạo thủ công:
```
POST /api/device
{
  "name": "Đèn trần phòng khách",
  "type": "light",
  "customerId": { "id": "CUSTOMER_ID" },
  "deviceProfileId": { "id": "LIGHT_PROFILE_ID" }
}

# Relation: Room CONTAINS Device
POST /api/relation
{
  "from": {"entityType": "ASSET", "id": "ROOM_ASSET_ID"},
  "to": {"entityType": "DEVICE", "id": "DEVICE_ID"},
  "type": "Contains"
}
```

## Cấu trúc Relation hoàn chỉnh

```
Customer
  └── Asset: "Nhà tôi" (type: smarthome_home, profile: SmartHome Home)
        │  server_attr: {scenes, automations, weather_location, members}
        │
        ├── [Contains] Asset: "Phòng khách" (type: smarthome_room, profile: SmartHome Room)
        │     │  server_attr: {icon: "living_room", order: 0}
        │     │
        │     ├── [Contains] Device: "Đèn trần" (profile: SmartHome Light)
        │     ├── [Contains] Device: "Điều hòa" (profile: SmartHome Air Conditioner)
        │     └── [Contains] Device: "Cảm biến nhiệt" (profile: SmartHome Temp/Humidity)
        │
        ├── [Contains] Asset: "Phòng ngủ" (type: smarthome_room)
        │     └── [Contains] Device: "Đèn ngủ" (profile: SmartHome Light)
        │
        └── Device: "Gateway" (profile: SmartHome Gateway)
              ├── Sub-device: "Đèn trần" (auto-managed by gateway protocol)
              ├── Sub-device: "Điều hòa"
              └── Sub-device: "Cảm biến nhiệt"
```

## Lưu ý quan trọng

1. **Rule Chain chỉ reactive**: Chạy khi có message đến, không phải cron. Dùng Scheduler Device nếu cần time-trigger.

2. **Related Attributes node**: Khi tìm từ Device → Home Asset qua 2 cấp relation (Device→Room→Home), set `maxLevel: 3` trong Related Attributes node.

3. **Gateway "Is gateway" flag**: Phải tick checkbox này khi tạo device, nếu không gateway protocol (v1/gateway/*) sẽ không hoạt động.

4. **Alarm propagation**: Đã cấu hình `propagateToOwner: true` để Customer nhận alarm từ devices.

5. **JSON attribute size**: ThingsBoard CE có giới hạn attribute value size (~64KB default). Nếu automations list quá lớn, cần tách thành nhiều attributes hoặc tăng limit trong config.

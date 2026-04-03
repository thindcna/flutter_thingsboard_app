# SmartHome Data Model Reference

Tài liệu tham khảo telemetry keys, attributes, RPC methods cho từng device type.
(Thông tin này dùng trong app Flutter, KHÔNG import vào ThingsBoard)

---

## Asset Types

### smarthome_home (Asset Profile: SmartHome Home)
**Server Attributes:**
| Key | Type | Mô tả |
|-----|------|--------|
| `scenes` | JSON array | Danh sách scenes (kịch bản) |
| `automations` | JSON array | Automation rules chạy trên server |
| `weather_location` | JSON object | `{lat, lon, city}` |
| `members` | JSON array | `[{user_id, role, name, added_at}]` |

**Telemetry:**
| Key | Type | Mô tả |
|-----|------|--------|
| `automation_log` | JSON string | Log kết quả chạy automation |

**Relations:** Home `Contains` → Room (smarthome_room)

### smarthome_room (Asset Profile: SmartHome Room)
**Server Attributes:**
| Key | Type | Mô tả |
|-----|------|--------|
| `icon` | string | Icon: living_room, bedroom, kitchen, bathroom, etc. |
| `order` | int | Thứ tự hiển thị (0 = đầu tiên) |

**Relations:** Room `Contains` → Device

---

## Device Types

### light (Device Profile: SmartHome Light)
| Telemetry | Shared Attributes | RPC Methods |
|-----------|-------------------|-------------|
| `brightness` (0-100) | `switch` (bool) | `setValue({brightness, color_temp, color_rgb})` |
| `color_temp` (2700-6500K) | `brightness_set` (int) | `toggle()` |
| `color_rgb` (#RRGGBB) | `color_temp_set` (int) | |
| `power_w` | `color_set` (string) | |
| | `mode` (string) | |

**Modes:** reading, relax, night, party, normal

### air_conditioner (Device Profile: SmartHome Air Conditioner)
| Telemetry | Shared Attributes | RPC Methods |
|-----------|-------------------|-------------|
| `temperature` (°C) | `power` (bool) | `setMode({mode})` |
| `humidity` (%) | `mode` (string) | `setTemp({target_temp})` |
| `power_w` | `target_temp` (16-30) | `toggle()` |
| `energy_kwh` | `fan_speed` (string) | `setValue({...})` |
| | `swing` (bool) | |

**Modes:** cool, heat, auto, dry, fan
**Fan speeds:** auto, low, medium, high

### smart_plug (Device Profile: SmartHome Smart Plug)
| Telemetry | Shared Attributes | RPC Methods |
|-----------|-------------------|-------------|
| `power_w` | `switch` (bool) | `toggle()` |
| `energy_kwh` | `countdown_seconds` (int) | `setCountdown({countdown_seconds})` |
| `voltage` (V) | | |
| `current` (A) | | |

### curtain (Device Profile: SmartHome Curtain)
| Telemetry | Shared Attributes | RPC Methods |
|-----------|-------------------|-------------|
| `position` (0-100%) | `command` (string) | `open()` |
| | `position_set` (int) | `close()` |
| | | `stop()` |
| | | `setPosition({position})` |

### door_sensor (Device Profile: SmartHome Door Window Sensor)
| Telemetry | Client Attributes | RPC Methods |
|-----------|-------------------|-------------|
| `contact` (bool: true=closed) | `last_open_time` | (none) |
| `battery` (%) | `last_close_time` | |

### motion_sensor (Device Profile: SmartHome Motion Sensor)
| Telemetry | Shared Attributes | RPC Methods |
|-----------|-------------------|-------------|
| `motion` (bool) | `sensitivity` (int) | (none) |
| `illuminance` (lux) | | |
| `battery` (%) | | |

### temp_humidity (Device Profile: SmartHome Temp Humidity Sensor)
| Telemetry | Shared Attributes | RPC Methods |
|-----------|-------------------|-------------|
| `temperature` (°C) | `temp_threshold_high` | (none) |
| `humidity` (%) | `temp_threshold_low` | |
| `battery` (%) | | |

### camera (Device Profile: SmartHome Camera)
| Telemetry | Server Attributes | Shared Attributes | RPC Methods |
|-----------|-------------------|-------------------|-------------|
| `motion_detected` (bool) | `stream_url` | `motion_detect_enabled` (bool) | `ptz_control({action})` |
| | `rtsp_url` | | `snapshot()` |

**PTZ actions:** up, down, left, right, zoom_in, zoom_out, stop

### gateway (Device Profile: SmartHome Gateway)
| Telemetry | Server Attributes | RPC Methods |
|-----------|-------------------|-------------|
| `cpu_usage` (%) | `firmware_version` | `set_automation({id, conditions, actions, ...})` |
| `memory_usage` (%) | `local_automations` (JSON) | `remove_automation({id})` |
| `uptime` (seconds) | | `start_pairing({device_type, timeout_seconds})` |
| `sub_device_count` | | `stop_pairing()` |
| | | `reboot()` |
| | | `get_sub_devices()` |

**Lưu ý:** Gateway device cần tick checkbox **"Is gateway"** trong ThingsBoard UI khi tạo device.

# SmartHome Custom Rule Nodes for ThingsBoard CE

Two custom rule engine nodes that solve the core limitations of built-in JS nodes:

| Node | Problem Solved |
|------|----------------|
| **SmartHomeConditionNode** | Multi-device condition evaluation — reads telemetry of N devices from DB in a single batch, evaluates ALL conditions in an automation rule simultaneously |
| **SmartHomeBulkCommandNode** | Bulk RPC to N devices — resolves device list from a room/home asset, sends `ToDeviceRpcRequest` directly by device UUID (no originator swapping needed) |

---

## Prerequisites

ThingsBoard CE 4.x running locally or on a server.

These nodes use TB's internal Java API (`TimeseriesService`, `RelationService`, `RuleEngineRpcService`).
You must build against the same TB version deployed on your server.

---

## Step 1 — Install TB JARs into local Maven repo

ThingsBoard does not publish to Maven Central. Install locally:

```bash
# Option A: build TB from source (one-time, takes ~10 minutes)
git clone https://github.com/thingsboard/thingsboard.git --branch release-4.0
cd thingsboard
mvn clean install -DskipTests -pl application,rule-engine/rule-engine-components,common/data

# Option B: copy from a running TB installation
# Copy {TB_HOME}/lib/thingsboard.jar to this directory, then:
mvn install:install-file \
  -Dfile=thingsboard.jar \
  -DgroupId=org.thingsboard \
  -DartifactId=rule-engine-components \
  -Dversion=4.0.0 \
  -Dpackaging=jar
```

Update `tb.version` in `pom.xml` to match your TB version exactly.

---

## Step 2 — Build the JAR

```bash
cd thingsboard-smarthome-nodes
mvn clean package -DskipTests
# Output: target/smarthome-rule-nodes-1.0.0.jar
```

---

## Step 3 — Deploy to ThingsBoard

```bash
# Copy JAR to TB extensions directory
sudo cp target/smarthome-rule-nodes-1.0.0.jar /usr/share/thingsboard/extensions/

# Restart ThingsBoard to pick up the new nodes
sudo systemctl restart thingsboard

# Verify in logs:
sudo journalctl -u thingsboard -f | grep SmartHome
```

After restart, the two new node types appear in the Rule Chain editor under the **Filter** and **Action** categories.

---

## Step 4 — Update the Rule Chain

Replace the two `TbRuleChainInputNode` placeholders in `smarthome_automation_engine.json` with the actual node types:

| Placeholder label | Replace with |
|---|---|
| `PLACEHOLDER: SmartHomeConditionNode` | `SmartHomeConditionNode` (category: Filter) |
| `PLACEHOLDER: SmartHomeBulkCommandNode` | `SmartHomeBulkCommandNode` (category: Action) |

---

## Node Configs

### SmartHomeConditionNode

| Field | Default | Description |
|-------|---------|-------------|
| `automationsAttributeKey` | `"automations"` | Server attribute key on Home Asset containing the rules JSON array |
| `attributeScope` | `"SERVER_SCOPE"` | Attribute scope |
| `deviceStateStalenesMs` | `300000` | Max age (ms) of device telemetry before it is considered stale and its condition is skipped |
| `queryTimeoutMs` | `5000` | DB query timeout (ms) |

### SmartHomeBulkCommandNode

| Field | Default | Description |
|-------|---------|-------------|
| `rpcTimeoutMs` | `10000` | Per-device RPC timeout (ms) |
| `executionMode` | `PARALLEL` | `PARALLEL` (all at once) or `SEQUENTIAL` (one by one) |
| `continueOnPartialFailure` | `true` | If false, stop on first failure |
| `relationTypeFilter` | `"Contains"` | Relation type for room/home → device resolution |
| `relationQueryTimeoutMs` | `3000` | RelationService query timeout (ms) |

---

## Input Message Format

### SmartHomeConditionNode

```
msgType:         POST_TELEMETRY_REQUEST | POST_ATTRIBUTES_REQUEST
originator:      Device entity (the triggering device)
metadata:
  originatorId      — UUID string (set by TbGetOriginatorFieldsNode before this node)
  homeAutomations   — JSON string of automation rules array (set by TbGetRelatedAttributeNode)
body:            { "temperature": 35, ... }  ← current telemetry
```

### SmartHomeBulkCommandNode — three modes

```json
// Mode 1: explicit device list
{ "mode": "device_list", "device_ids": ["uuid-A","uuid-B"], "command": "toggle", "params": {"power": false} }

// Mode 2: all devices in a room
{ "mode": "room", "room_asset_id": "uuid-room", "command": "toggle", "params": {"power": false} }

// Mode 3: all devices in a home (all rooms)
{ "mode": "home", "home_asset_id": "uuid-home", "command": "toggle", "params": {"power": false}, "filter_type": "light" }
```

---

## Known Limitations

- `time_schedule` (cron) condition type is not yet implemented — see `ConditionEvaluator.java:evaluateTimeSchedule`. Add Quartz or Spring's `CronExpression` as a dependency to implement.
- `sunrise_sunset`, `geofencing`, `weather` condition types are not yet implemented — log a warning and return false.
- `filter_type` (device profile filtering) in SmartHomeBulkCommandNode is not yet implemented — all resolved devices receive the command regardless of type.
- `ToDeviceRpcRequest` constructor signature may vary slightly between TB versions — check the actual class constructor in your TB version if build fails.

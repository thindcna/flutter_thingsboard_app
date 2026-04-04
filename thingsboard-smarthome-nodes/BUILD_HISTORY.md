# Build History — SmartHome Custom Rule Nodes

Tài liệu này ghi lại toàn bộ quá trình thiết lập môi trường build, các vấn đề gặp phải và cách giải quyết.
Dùng làm tham chiếu khi cần rebuild, cập nhật tính năng, hoặc migrate lên TB version mới.

---

## Môi trường

| Thành phần | Phiên bản |
|---|---|
| ThingsBoard CE | **4.3.1.1** (WSL Ubuntu 24.04) |
| Java | OpenJDK 17.0.18 (Ubuntu) |
| Maven | 3.8.7 (Ubuntu apt) |
| TB install path | `/usr/share/thingsboard/` |
| TB JAR | `/usr/share/thingsboard/bin/thingsboard.jar` (367MB fat JAR) |
| Extensions dir | `/usr/share/thingsboard/extensions/` |
| Config dir | `/usr/share/thingsboard/conf/` |

---

## Lần build đầu tiên (2026-04-04)

### Bước 1 — Cài Maven

```bash
wsl -d Ubuntu-24.04 -u root -- bash
apt-get install -y maven
```

Java 17 đã có sẵn theo TB. Maven chưa cài → cài qua apt.

---

### Bước 2 — Extract nested JARs từ TB fat JAR

TB không publish lên Maven Central. JARs cần thiết nằm bên trong fat JAR tại
`BOOT-INF/lib/`. Cần extract và install thủ công vào local Maven repo.

**Lệnh extract:**
```bash
cd /tmp/tb-jars
TB_VERSION="4.3.1.1"
FAT_JAR="/usr/share/thingsboard/bin/thingsboard.jar"

jar xf "$FAT_JAR" BOOT-INF/lib/rule-engine-api-${TB_VERSION}.jar
jar xf "$FAT_JAR" BOOT-INF/lib/rule-engine-components-${TB_VERSION}.jar
jar xf "$FAT_JAR" BOOT-INF/lib/dao-api-${TB_VERSION}.jar
jar xf "$FAT_JAR" BOOT-INF/lib/data-${TB_VERSION}.jar        # → install as common-data
jar xf "$FAT_JAR" BOOT-INF/lib/message-${TB_VERSION}.jar     # → install as common-message
jar xf "$FAT_JAR" BOOT-INF/lib/util-${TB_VERSION}.jar        # → install as common-util
```

**Lệnh install vào Maven repo:**
```bash
# rule-engine-api (TbNode, TbContext, RuleNode annotation, RuleEngineRpcService, ListeningExecutor)
mvn install:install-file -Dfile="BOOT-INF/lib/rule-engine-api-${TB_VERSION}.jar" \
  -DgroupId=org.thingsboard -DartifactId=rule-engine-api \
  -Dversion="$TB_VERSION" -Dpackaging=jar -DgeneratePom=true

# rule-engine-components (TbNodeUtils, built-in node helpers)
mvn install:install-file -Dfile="BOOT-INF/lib/rule-engine-components-${TB_VERSION}.jar" \
  -DgroupId=org.thingsboard -DartifactId=rule-engine-components \
  -Dversion="$TB_VERSION" -Dpackaging=jar -DgeneratePom=true

# dao-api (TimeseriesService, RelationService)
mvn install:install-file -Dfile="BOOT-INF/lib/dao-api-${TB_VERSION}.jar" \
  -DgroupId=org.thingsboard -DartifactId=dao-api \
  -Dversion="$TB_VERSION" -Dpackaging=jar -DgeneratePom=true

# data (DeviceId, TenantId, TsKvEntry, ComponentType, EntityRelation, EntityType)
mvn install:install-file -Dfile="BOOT-INF/lib/data-${TB_VERSION}.jar" \
  -DgroupId=org.thingsboard -DartifactId=common-data \
  -Dversion="$TB_VERSION" -Dpackaging=jar -DgeneratePom=true

# message (TbMsg, TbMsgMetaData)
mvn install:install-file -Dfile="BOOT-INF/lib/message-${TB_VERSION}.jar" \
  -DgroupId=org.thingsboard -DartifactId=common-message \
  -Dversion="$TB_VERSION" -Dpackaging=jar -DgeneratePom=true

# util (ListeningExecutor, AbstractListeningExecutor)
mvn install:install-file -Dfile="BOOT-INF/lib/util-${TB_VERSION}.jar" \
  -DgroupId=org.thingsboard -DartifactId=common-util \
  -Dversion="$TB_VERSION" -Dpackaging=jar -DgeneratePom=true
```

**Mapping JAR tên trong fat JAR → artifactId trong Maven:**

| JAR trong fat JAR | artifactId (Maven) | Chứa classes |
|---|---|---|
| `rule-engine-api-X.jar` | `rule-engine-api` | `TbNode`, `TbContext`, `RuleNode`, `TbNodeConfiguration`, `RuleEngineRpcService`, `ListeningExecutor` |
| `rule-engine-components-X.jar` | `rule-engine-components` | `TbNodeUtils`, built-in nodes |
| `dao-api-X.jar` | `dao-api` | `TimeseriesService`, `RelationService` |
| `data-X.jar` | `common-data` | `DeviceId`, `TenantId`, `TsKvEntry`, `ComponentType`, `EntityRelation`, `EntityType` |
| `message-X.jar` | `common-message` | `TbMsg`, `TbMsgMetaData`, `TbMsgType` |
| `util-X.jar` | `common-util` | `ListeningExecutor` |

---

### Bước 3 — Build

```bash
cd "/mnt/d/github/thingsboard/flutter_thingsboard_app_ai/thingsboard-smarthome-nodes"
mvn clean package -DskipTests
# Output: target/smarthome-rule-nodes-1.0.0.jar (2.3MB, fat JAR có shade jackson)
```

**Kết quả:** `BUILD SUCCESS` với các warnings về enum constants không có trong compile-time
classpath (swagger annotations, clustering mode...) — không ảnh hưởng runtime.

---

### Bước 4 — Deploy JAR

```bash
mkdir -p /usr/share/thingsboard/extensions
cp target/smarthome-rule-nodes-1.0.0.jar /usr/share/thingsboard/extensions/
chown thingsboard:thingsboard /usr/share/thingsboard/extensions/smarthome-rule-nodes-1.0.0.jar
```

---

### Bước 5 — Cấu hình package scan

**Vấn đề:** TB chỉ scan Spring component annotations trong các package được cấu hình.
Default là `org.thingsboard.server.extensions,org.thingsboard.rule.engine`.
Custom nodes ở `com.smarthome.rule` không được scan → không xuất hiện trong TB UI.

**Cấu hình tại:** `/usr/share/thingsboard/conf/thingsboard.conf`

```bash
# Thêm vào cuối file:
export PLUGINS_SCAN_PACKAGES=org.thingsboard.server.extensions,org.thingsboard.rule.engine,com.smarthome.rule
```

Config key trong `thingsboard.yml`:
```yaml
plugins:
  scan_packages: "${PLUGINS_SCAN_PACKAGES:org.thingsboard.server.extensions,org.thingsboard.rule.engine}"
```

---

### Bước 6 — Restart và verify

```bash
systemctl restart thingsboard
sleep 45  # chờ TB khởi động đầy đủ

# Verify qua API
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"tenant@thingsboard.org","password":"tenant"}' | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")

curl -s "http://localhost:8080/api/components?componentTypes=FILTER,ACTION" \
  -H "X-Authorization: Bearer $TOKEN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for c in data:
    if 'SmartHome' in c.get('name',''):
        print(c['name'], '-', c['clazz'])
"
```

**Kết quả verify:**
```
SmartHome Multi-Device Condition - com.smarthome.rule.condition.SmartHomeConditionNode
SmartHome Bulk Command - com.smarthome.rule.command.SmartHomeBulkCommandNode
```

---

## API ThingsBoard 4.3.1.1 — Ghi chú quan trọng

Những điểm khác với tài liệu cũ / TB 3.x:

### TbContext methods (verified từ javap)

```java
// RPC service — tên method là getRpcService(), KHÔNG phải getRuleEngineRpcService()
RuleEngineRpcService ctx.getRpcService()

// Gửi RPC tới device — 1 callback (không có onSuccess/onFailure riêng)
// Kiểm tra response.getError() để biết thành công hay thất bại
ctx.getRpcService().sendRpcRequestToDevice(
    RuleEngineDeviceRpcRequest request,
    Consumer<RuleEngineDeviceRpcResponse> callback
)

// Executor cho DB callbacks
ListeningExecutor ctx.getDbCallbackExecutor()

// Timeseries — trả về ListenableFuture (Guava, vẫn async)
ListenableFuture<List<TsKvEntry>> ctx.getTimeseriesService()
    .findLatest(TenantId, EntityId, Collection<String> keys)

// Relation
ListenableFuture<List<EntityRelation>> ctx.getRelationService()
    .findByFromAndTypeAsync(TenantId, EntityId, String relationType, RelationTypeGroup)
```

### RuleEngineDeviceRpcRequest — dùng Builder

```java
RuleEngineDeviceRpcRequest request = RuleEngineDeviceRpcRequest.builder()
    .tenantId(ctx.getTenantId())
    .deviceId(deviceId)
    .requestUUID(UUID.randomUUID())
    .requestId(0)
    .oneway(false)
    .persisted(false)
    .method("toggle")
    .body("{\"power\": false}")
    .expirationTime(System.currentTimeMillis() + timeoutMs)
    .restApiCall(false)
    .retries(0)
    .build();
```

### RuleEngineDeviceRpcResponse — check error

```java
Consumer<RuleEngineDeviceRpcResponse> callback = response -> {
    boolean success = response.getError().isEmpty();
    // response.getError() returns Optional<RpcError>
    // response.getResponse() returns Optional<String> (JSON response body)
};
```

### NodeConfiguration vs TbNodeConfiguration

```java
// Interface cho config class (implement này):
public class MyConfig implements NodeConfiguration<MyConfig> {
    @Override
    public MyConfig defaultConfiguration() { return new MyConfig(); }
}

// TbNodeConfiguration là final class (wrapper cho JsonNode):
// Truyền vào init(), convert ra config class dùng TbNodeUtils:
MyConfig config = TbNodeUtils.convert(configuration, MyConfig.class);
```

### @RuleNode annotation (required fields)

```java
@RuleNode(
    type = ComponentType.FILTER,   // hoặc ACTION, ENRICHMENT, TRANSFORMATION, EXTERNAL
    name = "Tên node hiển thị",
    configClazz = MyConfig.class,
    relationTypes = {"Success", "Failure"},  // output connection labels
    nodeDescription = "Mô tả ngắn",
    nodeDetails = "Mô tả chi tiết"
    // các field khác có default value
)
```

---

## Khi cần cập nhật code và rebuild

### Script rebuild nhanh (chạy trong WSL Ubuntu-24.04 as root)

```bash
#!/bin/bash
set -e

PROJECT="/mnt/d/github/thingsboard/flutter_thingsboard_app_ai/thingsboard-smarthome-nodes"
DEPLOY_DIR="/usr/share/thingsboard/extensions"
JAR_NAME="smarthome-rule-nodes-1.0.0.jar"

echo "=== Build ==="
cd "$PROJECT"
mvn clean package -DskipTests -q

echo "=== Deploy ==="
cp "target/$JAR_NAME" "$DEPLOY_DIR/"
chown thingsboard:thingsboard "$DEPLOY_DIR/$JAR_NAME"
echo "Deployed: $(ls -lh $DEPLOY_DIR/$JAR_NAME)"

echo "=== Restart ThingsBoard ==="
systemctl restart thingsboard
echo "Waiting 45s for startup..."
sleep 45

echo "=== Verify ==="
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"tenant@thingsboard.org","password":"tenant"}' | \
  python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")

curl -s "http://localhost:8080/api/components?componentTypes=FILTER,ACTION" \
  -H "X-Authorization: Bearer $TOKEN" | \
  python3 -c "
import sys, json
for c in json.load(sys.stdin):
    if 'SmartHome' in c.get('name',''):
        print('OK:', c['name'])
"
```

---

### Khi nâng cấp TB version

1. Kiểm tra version mới: `dpkg -l thingsboard`
2. Cập nhật `tb.version` trong [pom.xml](pom.xml)
3. Extract lại 6 JARs từ fat JAR mới (xem Bước 2)
4. Rebuild: `mvn clean package -DskipTests`
5. Kiểm tra compile errors → fix API thay đổi nếu có
6. Deploy và restart

---

### Khi thêm tính năng mới

**Thêm condition type mới** (vd: `weather`, `sunrise_sunset`):
- Sửa `ConditionEvaluator.java` → thêm `case` vào switch trong `evaluateOne()`
- Các field trong condition đã có sẵn trong `AutomationRuleParser.Condition` (latitude, longitude, event, offsetMinutes)
- Với `time_schedule` (cron): thêm dependency `org.quartz-scheduler:quartz` vào pom.xml,
  dùng `CronExpression.isValidExpression()` và tính toán next fire time

**Thêm action type mới** trong SmartHomeBulkCommandNode:
- Hiện node chỉ xử lý `device_command` (gửi RPC)
- Để xử lý `send_notification`: dùng `ctx.getNotificationCenter()` hoặc `ctx.getAlarmService()`
- Để xử lý `run_scene`: cần đọc scene từ Home Asset attribute rồi execute từng action

**Thêm `filter_type` (lọc theo device profile)**:
- Trong `SmartHomeBulkCommandNode.sendCommandToDevices()` sau khi resolve devices
- Gọi `ctx.getDeviceService().findDevicesByIds(tenantId, deviceIds)` để lấy Device objects
- Filter theo `device.getDeviceProfileName()` hoặc `device.getType()`

---

## Troubleshooting

| Triệu chứng | Nguyên nhân | Cách fix |
|---|---|---|
| Nodes không xuất hiện trong TB UI | `com.smarthome.rule` chưa trong `PLUGINS_SCAN_PACKAGES` | Thêm vào `/usr/share/thingsboard/conf/thingsboard.conf` → restart |
| `BUILD FAILURE: package does not exist` | JAR chưa install vào Maven repo | Re-run Bước 2 với TB version hiện tại |
| `unknown enum constant ComponentClusteringMode.ENABLED` | Warning vô hại — class file thiếu swagger/clustering annotation | Bỏ qua, không ảnh hưởng runtime |
| Node hiển thị nhưng config form trống | Config class không implement `NodeConfiguration` đúng cách | Kiểm tra `defaultConfiguration()` method |
| RPC không gửi được | `deviceId` không hợp lệ hoặc device offline | Kiểm tra log TB: `journalctl -u thingsboard -f` |
| `getError()` luôn có giá trị dù device online | `oneway=false` nhưng device không respond trong timeout | Tăng `rpcTimeoutMs` trong node config |


## Rebuild
cd /mnt/d/github/thingsboard/flutter_thingsboard_app_ai/thingsboard-smarthome-nodes
mvn clean package -DskipTests
cp target/smarthome-rule-nodes-1.0.0.jar /usr/share/thingsboard/extensions/
systemctl restart thingsboard
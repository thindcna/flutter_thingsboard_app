package com.smarthome.rule.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.*;
import com.smarthome.rule.command.SmartHomeBulkCommandConfig.ExecutionMode;
import com.smarthome.rule.util.RelationResolver;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.rule.engine.api.*;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * SmartHome Bulk Command Node
 *
 * Sends an RPC command to MULTIPLE devices simultaneously.
 *
 * The target devices are resolved from the message originator (Asset entity):
 *   - If originator is a Room Asset  → finds all devices directly in that room
 *   - If originator is a Home Asset  → finds all devices across all rooms
 *   No need to pass room_asset_id / home_asset_id in the message body.
 *
 * Special modes (set "mode" in body to override originator-based resolution):
 *   - "device_list":   explicit list of device UUIDs in "device_ids" field
 *   - "multi_command": per-device commands in "commands" array (used by automation engine)
 *
 * Minimal input body (originator-based, preferred for app direct control):
 * {
 *   "command":     "toggle",
 *   "params":      {"power": false},
 *   "filter_type": "light"   // optional
 * }
 *
 * Output connections:
 *   - "Success"         — all devices received the command
 *   - "Partial Success" — some devices succeeded, some failed (continueOnPartialFailure=true)
 *   - "Failure"         — all devices failed, or relation query failed
 */
@RuleNode(
        type = ComponentType.ACTION,
        name = "SmartHome Bulk Command",
        configClazz = SmartHomeBulkCommandConfig.class,
        relationTypes = {"Success", "Partial Success", "Failure"},
        nodeDescription = "Sends an RPC command to multiple SmartHome devices (list, room, or home)",
        nodeDetails = "Resolves device list via RelationService for room/home modes. " +
                "Sends RuleEngineDeviceRpcRequest to each device by UUID. Parallel or sequential execution."
)
public class SmartHomeBulkCommandNode implements TbNode {

    private static final Logger log = LoggerFactory.getLogger(SmartHomeBulkCommandNode.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SmartHomeBulkCommandConfig config;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        config = TbNodeUtils.convert(configuration, SmartHomeBulkCommandConfig.class);
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        Map<String, Object> body;
        try {
            body = MAPPER.readValue(msg.getData(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.error("Cannot parse msg body as JSON", e);
            ctx.tellFailure(msg, e);
            return;
        }

        String mode = (String) body.getOrDefault("mode", "");

        // multi_command: per-device commands, used by automation engine for parallel fan-out
        if ("multi_command".equals(mode)) {
            handleMultiCommand(ctx, msg, body);
            return;
        }

        String command = (String) body.get("command");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.containsKey("params")
                ? (Map<String, Object>) body.get("params")
                : Map.of();

        if (command == null || command.isBlank()) {
            ctx.tellFailure(msg, new IllegalArgumentException("msg body missing 'command' field"));
            return;
        }

        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(params);
        } catch (IOException e) {
            ctx.tellFailure(msg, e);
            return;
        }

        ListeningExecutor dbExec = ctx.getDbCallbackExecutor();
        RelationResolver resolver = new RelationResolver(
                ctx.getRelationService(), ctx.getTenantId(), config.getRelationTypeFilter(), dbExec);

        ListenableFuture<List<DeviceId>> devicesFuture;

        if ("device_list".equals(mode)) {
            // Explicit device list — used when targeting specific devices by UUID
            @SuppressWarnings("unchecked")
            List<String> rawIds = (List<String>) body.getOrDefault("device_ids", List.of());
            List<DeviceId> deviceIds = rawIds.stream()
                    .map(id -> new DeviceId(UUID.fromString(id)))
                    .collect(Collectors.toList());
            devicesFuture = Futures.immediateFuture(deviceIds);
        } else {
            // Default: resolve devices from the message originator (Room or Home Asset).
            // App POSTs to /api/plugins/telemetry/ASSET/{roomOrHomeId}/timeseries/ANY
            // → originator IS the target asset, no need to pass IDs in the body.
            devicesFuture = resolver.resolveDevicesFromEntity(msg.getOriginator().getId());
        }

        final String finalCommand    = command;
        final String finalParamsJson = paramsJson;

        Futures.addCallback(devicesFuture, new FutureCallback<List<DeviceId>>() {
            @Override
            public void onSuccess(List<DeviceId> deviceIds) {
                if (deviceIds == null || deviceIds.isEmpty()) {
                    ctx.tellFailure(msg, new RuntimeException(
                            "No devices found via relations from originator " + msg.getOriginator()));
                    return;
                }
                sendCommandToDevices(ctx, msg, deviceIds, finalCommand, finalParamsJson);
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to resolve devices from originator {}", msg.getOriginator(), t);
                ctx.tellFailure(msg, t);
            }
        }, dbExec);
    }

    /**
     * Handles mode=multi_command: each entry in "commands" array targets a specific device
     * with its own command and params. All RPCs are fired in parallel (true simultaneous).
     *
     * Input body:
     * {
     *   "mode": "multi_command",
     *   "commands": [
     *     {"device_id": "uuid-A", "command": "toggle", "params": {"power": true}},
     *     {"device_id": "uuid-B", "command": "setTemp", "params": {"temp": 24}}
     *   ]
     * }
     */
    @SuppressWarnings("unchecked")
    private void handleMultiCommand(TbContext ctx, TbMsg msg, Map<String, Object> body) {
        List<Map<String, Object>> commands = (List<Map<String, Object>>) body.get("commands");
        if (commands == null || commands.isEmpty()) {
            ctx.tellFailure(msg, new IllegalArgumentException("mode=multi_command requires non-empty 'commands' array"));
            return;
        }

        int total = commands.size();
        List<DeviceResult> results = Collections.synchronizedList(new ArrayList<>(total));
        AtomicInteger done = new AtomicInteger(0);
        AtomicBoolean alreadyFailed = new AtomicBoolean(false);

        for (Map<String, Object> entry : commands) {
            String deviceIdStr = (String) entry.get("device_id");
            String command     = (String) entry.get("command");
            Map<String, Object> params = entry.containsKey("params")
                    ? (Map<String, Object>) entry.get("params")
                    : Map.of();

            if (deviceIdStr == null || command == null) {
                results.add(new DeviceResult(String.valueOf(deviceIdStr), "failed", "missing device_id or command"));
                if (done.incrementAndGet() == total && !alreadyFailed.get()) {
                    finishAndForward(ctx, msg, results, total);
                }
                continue;
            }

            DeviceId deviceId;
            try {
                deviceId = new DeviceId(UUID.fromString(deviceIdStr));
            } catch (IllegalArgumentException e) {
                results.add(new DeviceResult(deviceIdStr, "failed", "invalid device_id UUID"));
                if (done.incrementAndGet() == total && !alreadyFailed.get()) {
                    finishAndForward(ctx, msg, results, total);
                }
                continue;
            }

            String paramsJson;
            try {
                paramsJson = MAPPER.writeValueAsString(params);
            } catch (IOException e) {
                results.add(new DeviceResult(deviceIdStr, "failed", "params serialization error"));
                if (done.incrementAndGet() == total && !alreadyFailed.get()) {
                    finishAndForward(ctx, msg, results, total);
                }
                continue;
            }

            RuleEngineDeviceRpcRequest rpcRequest = buildRpcRequest(ctx, deviceId, command, paramsJson);
            final String finalDeviceIdStr = deviceIdStr;

            ctx.getRpcService().sendRpcRequestToDevice(rpcRequest, response -> {
                boolean success = response.getError().isEmpty();
                String errorMsg = response.getError().map(Enum::name).orElse(null);
                results.add(new DeviceResult(finalDeviceIdStr, success ? "success" : "failed", errorMsg));

                if (!success && !config.isContinueOnPartialFailure()) {
                    if (alreadyFailed.compareAndSet(false, true)) {
                        buildAndForward(ctx, msg, results, total, "Failure");
                    }
                    return;
                }

                if (done.incrementAndGet() == total && !alreadyFailed.get()) {
                    finishAndForward(ctx, msg, results, total);
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void sendCommandToDevices(TbContext ctx, TbMsg originalMsg,
                                       List<DeviceId> deviceIds,
                                       String command, String paramsJson) {
        if (config.getExecutionMode() == ExecutionMode.PARALLEL) {
            sendParallel(ctx, originalMsg, deviceIds, command, paramsJson);
        } else {
            sendSequential(ctx, originalMsg, deviceIds, command, paramsJson, 0, new ArrayList<>());
        }
    }

    // ─── PARALLEL mode ────────────────────────────────────────────────────────

    private void sendParallel(TbContext ctx, TbMsg originalMsg,
                               List<DeviceId> deviceIds,
                               String command, String paramsJson) {
        int total = deviceIds.size();
        List<DeviceResult> results = Collections.synchronizedList(new ArrayList<>(total));
        AtomicInteger done = new AtomicInteger(0);
        AtomicBoolean alreadyFailed = new AtomicBoolean(false);

        for (DeviceId deviceId : deviceIds) {
            RuleEngineDeviceRpcRequest rpcRequest = buildRpcRequest(ctx, deviceId, command, paramsJson);

            ctx.getRpcService().sendRpcRequestToDevice(rpcRequest, response -> {
                boolean success = response.getError().isEmpty();
                String errorMsg = response.getError().map(Enum::name).orElse(null);
                results.add(new DeviceResult(deviceId.getId().toString(), success ? "success" : "failed", errorMsg));

                if (!success && !config.isContinueOnPartialFailure()) {
                    if (alreadyFailed.compareAndSet(false, true)) {
                        buildAndForward(ctx, originalMsg, results, total, "Failure");
                    }
                    return;
                }

                if (done.incrementAndGet() == total && !alreadyFailed.get()) {
                    finishAndForward(ctx, originalMsg, results, total);
                }
            });
        }
    }

    // ─── SEQUENTIAL mode ──────────────────────────────────────────────────────

    private void sendSequential(TbContext ctx, TbMsg originalMsg,
                                 List<DeviceId> deviceIds, String command, String paramsJson,
                                 int index, List<DeviceResult> results) {
        if (index >= deviceIds.size()) {
            finishAndForward(ctx, originalMsg, results, deviceIds.size());
            return;
        }

        DeviceId deviceId = deviceIds.get(index);
        RuleEngineDeviceRpcRequest rpcRequest = buildRpcRequest(ctx, deviceId, command, paramsJson);

        ctx.getRpcService().sendRpcRequestToDevice(rpcRequest, response -> {
            boolean success = response.getError().isEmpty();
            String errorMsg = response.getError().map(Enum::name).orElse(null);
            results.add(new DeviceResult(deviceId.getId().toString(), success ? "success" : "failed", errorMsg));

            if (!success && !config.isContinueOnPartialFailure()) {
                buildAndForward(ctx, originalMsg, results, deviceIds.size(), "Failure");
                return;
            }
            sendSequential(ctx, originalMsg, deviceIds, command, paramsJson, index + 1, results);
        });
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RuleEngineDeviceRpcRequest buildRpcRequest(TbContext ctx, DeviceId deviceId,
                                                        String method, String paramsJson) {
        return RuleEngineDeviceRpcRequest.builder()
                .tenantId(ctx.getTenantId())
                .deviceId(deviceId)
                .requestUUID(UUID.randomUUID())
                .requestId(0)
                .oneway(false)
                .persisted(false)
                .method(method)
                .body(paramsJson)
                .expirationTime(System.currentTimeMillis() + config.getRpcTimeoutMs())
                .restApiCall(false)
                .retries(0)
                .build();
    }

    private void finishAndForward(TbContext ctx, TbMsg originalMsg,
                                   List<DeviceResult> results, int total) {
        long successCount = results.stream().filter(r -> "success".equals(r.status)).count();
        String relation;
        if (successCount == total)      relation = "Success";
        else if (successCount == 0)     relation = "Failure";
        else                            relation = "Partial Success";
        buildAndForward(ctx, originalMsg, results, total, relation);
    }

    private void buildAndForward(TbContext ctx, TbMsg originalMsg,
                                  List<DeviceResult> results, int total, String relation) {
        try {
            long successCount = results.stream().filter(r -> "success".equals(r.status)).count();
            Map<String, Object> outputBody = new LinkedHashMap<>();
            outputBody.put("totalDevices",  total);
            outputBody.put("successCount",  successCount);
            outputBody.put("failedCount",   total - successCount);
            outputBody.put("results",       results);

            String outputJson = MAPPER.writeValueAsString(outputBody);
            TbMsgMetaData meta = originalMsg.getMetaData().copy();
            meta.putValue("bulkCmdSuccess", String.valueOf(successCount));
            meta.putValue("bulkCmdFailed",  String.valueOf(total - successCount));

            TbMsg outMsg = ctx.newMsg(originalMsg.getQueueName(), "BULK_COMMAND_RESULT",
                    originalMsg.getOriginator(), originalMsg.getCustomerId(), meta, outputJson);
            ctx.tellNext(outMsg, relation);

        } catch (Exception e) {
            log.error("Error building bulk command result", e);
            ctx.tellFailure(originalMsg, e);
        }
    }

    // ─── inner result record ──────────────────────────────────────────────────

    public static class DeviceResult {
        public final String deviceId;
        public final String status;
        public final String error;

        public DeviceResult(String deviceId, String status, String error) {
            this.deviceId = deviceId;
            this.status   = status;
            this.error    = error;
        }
    }

    @Override
    public void destroy() {}
}

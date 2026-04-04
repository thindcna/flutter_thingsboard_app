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
 * Sends an RPC command to MULTIPLE devices simultaneously:
 *   - Mode "device_list": explicit list of device UUIDs
 *   - Mode "room":        all devices contained in a room asset
 *   - Mode "home":        all devices in all rooms of a home asset
 *
 * Input message body (one of three modes — see DATA_MODEL_REFERENCE.md):
 * {
 *   "mode": "device_list",
 *   "device_ids": ["uuid-A", "uuid-B"],
 *   "command": "toggle",
 *   "params": {"power": false},
 *   "filter_type": null
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

        String mode    = (String) body.getOrDefault("mode", "device_list");
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

        switch (mode) {
            case "device_list" -> {
                @SuppressWarnings("unchecked")
                List<String> rawIds = (List<String>) body.getOrDefault("device_ids", List.of());
                List<DeviceId> deviceIds = rawIds.stream()
                        .map(id -> new DeviceId(UUID.fromString(id)))
                        .collect(Collectors.toList());
                devicesFuture = Futures.immediateFuture(deviceIds);
            }
            case "room" -> {
                String roomId = (String) body.get("room_asset_id");
                if (roomId == null) {
                    ctx.tellFailure(msg, new IllegalArgumentException("mode=room requires 'room_asset_id'"));
                    return;
                }
                devicesFuture = resolver.resolveDevicesInRoom(UUID.fromString(roomId));
            }
            case "home" -> {
                String homeId = (String) body.get("home_asset_id");
                if (homeId == null) {
                    ctx.tellFailure(msg, new IllegalArgumentException("mode=home requires 'home_asset_id'"));
                    return;
                }
                devicesFuture = resolver.resolveDevicesInHome(UUID.fromString(homeId));
            }
            default -> {
                ctx.tellFailure(msg, new IllegalArgumentException("Unknown mode: " + mode));
                return;
            }
        }

        final String finalCommand   = command;
        final String finalParamsJson = paramsJson;

        Futures.addCallback(devicesFuture, new FutureCallback<List<DeviceId>>() {
            @Override
            public void onSuccess(List<DeviceId> deviceIds) {
                if (deviceIds == null || deviceIds.isEmpty()) {
                    ctx.tellFailure(msg, new RuntimeException("No devices resolved for mode=" + mode));
                    return;
                }
                sendCommandToDevices(ctx, msg, deviceIds, finalCommand, finalParamsJson);
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to resolve devices for mode={}", mode, t);
                ctx.tellFailure(msg, t);
            }
        }, dbExec);
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

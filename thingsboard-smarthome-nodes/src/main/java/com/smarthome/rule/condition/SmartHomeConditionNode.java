package com.smarthome.rule.condition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.smarthome.rule.util.AutomationRuleParser;
import com.smarthome.rule.util.AutomationRuleParser.AutomationRule;
import com.smarthome.rule.util.AutomationRuleParser.Condition;
import com.smarthome.rule.util.ConditionEvaluator;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.rule.engine.api.*;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * SmartHome Multi-Device Condition Node
 *
 * Evaluates server-side SmartHome automation rules that may reference
 * multiple devices. Reads all referenced device telemetry in a single
 * batch from TimeseriesService (DB), so every condition is evaluated
 * against real current state — not just the telemetry of the triggering device.
 *
 * Input message:
 *   - originator: the Device that just sent telemetry
 *   - metadata.originatorId: UUID of trigger device  (set by TbGetOriginatorFieldsNode)
 *   - metadata.homeAutomations: JSON automation array (set by TbGetRelatedAttributeNode)
 *     If missing, node returns "No Match" (upstream node must supply it).
 *
 * Output connections:
 *   - "Matched"  — one or more rules triggered; msg body is the triggered-rules JSON
 *   - "No Match" — no rule triggered
 *   - "Failure"  — JSON parse error, missing metadata, or DB error
 */
@RuleNode(
        type = ComponentType.FILTER,
        name = "SmartHome Multi-Device Condition",
        configClazz = SmartHomeConditionConfig.class,
        relationTypes = {"Matched", "No Match", "Failure"},
        nodeDescription = "Evaluates SmartHome automation conditions across multiple devices",
        nodeDetails = "Reads the 'homeAutomations' metadata key (JSON automation rules). " +
                "Batch-queries latest telemetry for all referenced devices from the DB, then " +
                "evaluates each rule's conditions. Outputs 'Matched' if any rule triggered."
)
public class SmartHomeConditionNode implements TbNode {

    private static final Logger log = LoggerFactory.getLogger(SmartHomeConditionNode.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SmartHomeConditionConfig config;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        config = TbNodeUtils.convert(configuration, SmartHomeConditionConfig.class);
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        String automationsJson = msg.getMetaData().getValue("homeAutomations");
        if (automationsJson == null || automationsJson.isBlank()) {
            log.warn("homeAutomations not found in metadata; make sure TbGetRelatedAttributeNode runs first");
            ctx.tellNext(msg, "No Match");
            return;
        }

        String originatorIdStr = msg.getMetaData().getValue("originatorId");
        UUID triggerDeviceId = null;
        if (originatorIdStr != null && !originatorIdStr.isBlank()) {
            try {
                triggerDeviceId = UUID.fromString(originatorIdStr);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid originatorId in metadata: {}", originatorIdStr);
            }
        }
        if (triggerDeviceId == null) {
            // Fall back to msg originator
            triggerDeviceId = msg.getOriginator().getId();
        }

        // Parse trigger device's current telemetry from msg body
        Map<String, Object> triggerData;
        try {
            triggerData = MAPPER.readValue(msg.getData(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.warn("Could not parse msg body as JSON: {}", e.getMessage());
            triggerData = Map.of();
        }

        // Parse automation rules
        List<AutomationRule> rules;
        try {
            rules = AutomationRuleParser.parseRules(automationsJson);
        } catch (IOException e) {
            log.error("Failed to parse homeAutomations JSON", e);
            ctx.tellFailure(msg, e);
            return;
        }

        if (rules.isEmpty()) {
            ctx.tellNext(msg, "No Match");
            return;
        }

        // Filter only server-side, enabled rules
        List<AutomationRule> serverRules = rules.stream()
                .filter(r -> r.isEnabled() && "server".equalsIgnoreCase(r.getExecutionTarget()))
                .collect(Collectors.toList());

        if (serverRules.isEmpty()) {
            ctx.tellNext(msg, "No Match");
            return;
        }

        // Collect all device IDs referenced in conditions (across all rules)
        Set<DeviceId> deviceIds = collectReferencedDevices(serverRules);

        // Collect all telemetry keys needed per device
        Map<DeviceId, Set<String>> keysPerDevice = collectKeysPerDevice(serverRules);

        UUID finalTriggerDeviceId = triggerDeviceId;
        Map<String, Object> finalTriggerData = triggerData;
        TenantId tenantId = ctx.getTenantId();
        ListeningExecutor dbExec = ctx.getDbCallbackExecutor();

        // Batch query latest telemetry for all referenced devices
        List<DeviceId> orderedDeviceIds = deviceIds.stream()
                .filter(keysPerDevice::containsKey)
                .collect(Collectors.toList());

        List<ListenableFuture<List<TsKvEntry>>> futures = orderedDeviceIds.stream()
                .map(deviceId -> {
                    List<String> keys = new ArrayList<>(keysPerDevice.get(deviceId));
                    return ctx.getTimeseriesService().findLatest(tenantId, deviceId, keys);
                })
                .collect(Collectors.toList());

        ListenableFuture<List<List<TsKvEntry>>> allFutures = Futures.allAsList(futures);

        Futures.addCallback(allFutures, new FutureCallback<List<List<TsKvEntry>>>() {

            @Override
            public void onSuccess(List<List<TsKvEntry>> allResults) {
                // Build snapshot: DeviceId → (attributeKey → TsKvEntry)
                Map<DeviceId, Map<String, TsKvEntry>> snapshot = new HashMap<>();
                for (int i = 0; i < orderedDeviceIds.size(); i++) {
                    DeviceId deviceId = orderedDeviceIds.get(i);
                    List<TsKvEntry> entries = allResults.get(i);
                    if (entries != null) {
                        Map<String, TsKvEntry> kvMap = new HashMap<>();
                        for (TsKvEntry e : entries) {
                            kvMap.put(e.getKey(), e);
                        }
                        snapshot.put(deviceId, kvMap);
                    }
                }

                // Evaluate each rule against the snapshot
                List<AutomationRule> matchedRules = new ArrayList<>();
                for (AutomationRule rule : serverRules) {
                    ConditionEvaluator.Result result = ConditionEvaluator.evaluate(
                            rule, snapshot, finalTriggerDeviceId,
                            finalTriggerData, config.getDeviceStateStalenesMs());
                    if (result.matched) {
                        matchedRules.add(rule);
                        log.debug("Rule '{}' MATCHED for device {}", rule.getName(), finalTriggerDeviceId);
                    }
                }

                if (matchedRules.isEmpty()) {
                    ctx.tellNext(msg, "No Match");
                    return;
                }

                // Emit one EXECUTE_ACTION message per action across all matched rules.
                // Fan-out via enqueueForTellNext() — all actions run in parallel,
                // rule chain graph stays a DAG (no cycles needed).
                try {
                    int totalActions = 0;
                    TbMsgMetaData baseMeta = msg.getMetaData().copy();
                    baseMeta.putValue("triggerDeviceId",   finalTriggerDeviceId.toString());
                    baseMeta.putValue("triggerDeviceName", msg.getMetaData().getValue("originatorName"));
                    baseMeta.putValue("triggerTimestamp",  String.valueOf(System.currentTimeMillis()));

                    for (AutomationRule rule : matchedRules) {
                        List<AutomationRuleParser.Action> actions = rule.getActions();
                        if (actions == null || actions.isEmpty()) continue;

                        for (AutomationRuleParser.Action action : actions) {
                            TbMsgMetaData actionMeta = baseMeta.copy();
                            actionMeta.putValue("actionRuleId",   rule.getId());
                            actionMeta.putValue("actionRuleName", rule.getName());

                            String actionJson = MAPPER.writeValueAsString(action);
                            TbMsg actionMsg = ctx.newMsg(
                                    msg.getQueueName(),
                                    "EXECUTE_ACTION",
                                    msg.getOriginator(),
                                    msg.getCustomerId(),
                                    actionMeta,
                                    actionJson);

                            if (totalActions == 0) {
                                ctx.tellNext(actionMsg, "Matched");
                            } else {
                                ctx.enqueueForTellNext(actionMsg, "Matched", () -> {}, t ->
                                        log.warn("Failed to enqueue action for rule '{}': {}", rule.getName(), t.getMessage()));
                            }
                            totalActions++;
                        }
                    }

                    if (totalActions == 0) {
                        ctx.tellNext(msg, "No Match");
                    }

                } catch (Exception e) {
                    ctx.tellFailure(msg, e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to query device telemetry for condition evaluation", t);
                ctx.tellFailure(msg, t);
            }

        }, dbExec);
    }

    @Override
    public void destroy() {
        // no resources to release
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Collect all unique device IDs referenced in device_state conditions. */
    private Set<DeviceId> collectReferencedDevices(List<AutomationRule> rules) {
        Set<DeviceId> ids = new HashSet<>();
        for (AutomationRule rule : rules) {
            if (rule.getConditions() == null) continue;
            for (Condition c : rule.getConditions()) {
                if ("device_state".equals(c.getType()) && c.getDeviceId() != null) {
                    try {
                        ids.add(new DeviceId(UUID.fromString(c.getDeviceId())));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid device_id in condition: {}", c.getDeviceId());
                    }
                }
                if ("device_offline".equals(c.getType()) && c.getDeviceId() != null) {
                    try {
                        ids.add(new DeviceId(UUID.fromString(c.getDeviceId())));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid device_id in device_offline condition: {}", c.getDeviceId());
                    }
                }
            }
        }
        return ids;
    }

    /** Map each device to the set of telemetry keys needed across all rules. */
    private Map<DeviceId, Set<String>> collectKeysPerDevice(List<AutomationRule> rules) {
        Map<DeviceId, Set<String>> result = new HashMap<>();
        for (AutomationRule rule : rules) {
            if (rule.getConditions() == null) continue;
            for (Condition c : rule.getConditions()) {
                if ("device_state".equals(c.getType())
                        && c.getDeviceId() != null
                        && c.getAttribute() != null) {
                    try {
                        DeviceId id = new DeviceId(UUID.fromString(c.getDeviceId()));
                        result.computeIfAbsent(id, k -> new HashSet<>()).add(c.getAttribute());
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return result;
    }
}

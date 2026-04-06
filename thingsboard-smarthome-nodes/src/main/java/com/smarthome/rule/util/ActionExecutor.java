package com.smarthome.rule.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthome.rule.util.AutomationRuleParser.Action;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared utility for fanning out automation/scene action lists as ThingsBoard messages.
 *
 * Actions are split at "delay" boundaries. Delay durations are accumulated (cumulative
 * from trigger time) so each group knows exactly when to fire.
 *
 * Example: [cmd_A, cmd_B, delay_5s, cmd_C, delay_3s, cmd_D]
 *   → emit immediately: multi_command {A, B}
 *   → emit deferred 5s: {type:"delay", seconds:5, deferred_commands:[C]}
 *   → emit deferred 8s: {type:"delay", seconds:8, deferred_commands:[D]}
 *
 * Used by both SmartHomeConditionNode (automation) and SmartHomeSceneExecutorNode (scene).
 */
public class ActionExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────────

    public static class ActionGroup {
        public final int cumulativeSeconds;
        public final List<Map<String, Object>> deviceCommands = new ArrayList<>();
        public final List<Action> otherActions = new ArrayList<>();

        public ActionGroup(int cumulativeSeconds) {
            this.cumulativeSeconds = cumulativeSeconds;
        }
    }

    /**
     * Split an ordered action list into groups at delay boundaries.
     * Delay durations are accumulated so each group carries its absolute fire time.
     */
    public static List<ActionGroup> splitByDelays(List<Action> actions) {
        List<ActionGroup> groups = new ArrayList<>();
        ActionGroup current = new ActionGroup(0);

        for (Action action : actions) {
            if ("delay".equals(action.getType())) {
                groups.add(current);
                current = new ActionGroup(current.cumulativeSeconds + action.getSeconds());
            } else if ("device_command".equals(action.getType()) && action.getDeviceId() != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("device_id", action.getDeviceId());
                entry.put("command",   action.getCommand());
                entry.put("params",    action.getParams() != null ? action.getParams() : Map.of());
                current.deviceCommands.add(entry);
            } else {
                current.otherActions.add(action);
            }
        }
        groups.add(current);
        return groups;
    }

    /**
     * Emit one TB message per action group:
     *   - Immediate group (cumulativeSeconds == 0):
     *       device_command actions → single "multi_command" message (all RPCs fire in parallel)
     *       other actions (notification, run_scene, …) → individual messages
     *   - Deferred group (cumulativeSeconds > 0):
     *       wrapped as a "delay" message carrying deferred_commands and deferred_other
     *       the Delay node holds it, then "Execute Deferred" transform fires commands
     *
     * The first message uses ctx.tellNext(); subsequent ones use ctx.enqueueForTellNext()
     * so they all process in parallel without blocking each other.
     *
     * @return number of messages emitted (0 means no actions to execute)
     */
    public static int fanOut(TbContext ctx, TbMsg triggerMsg, TbMsgMetaData baseMeta,
                              List<Action> actions, String relation, Logger log) throws Exception {
        List<ActionGroup> groups = splitByDelays(actions);
        boolean first = true;
        int emitted = 0;

        for (ActionGroup group : groups) {
            if (group.deviceCommands.isEmpty() && group.otherActions.isEmpty()) continue;

            if (group.cumulativeSeconds == 0) {
                // Immediate: all device_commands → one multi_command
                if (!group.deviceCommands.isEmpty()) {
                    TbMsg m = buildMultiCommandMsg(ctx, triggerMsg, baseMeta, group.deviceCommands);
                    first = emit(ctx, m, relation, first, log);
                    emitted++;
                }
                // Other action types each get their own message
                for (Action other : group.otherActions) {
                    TbMsg m = buildActionMsg(ctx, triggerMsg, baseMeta, other);
                    first = emit(ctx, m, relation, first, log);
                    emitted++;
                }
            } else {
                // Deferred: wrap in delay message, Delay node holds it, Execute Deferred fires it
                Map<String, Object> deferredBody = new LinkedHashMap<>();
                deferredBody.put("type",              "delay");
                deferredBody.put("seconds",           group.cumulativeSeconds);
                deferredBody.put("deferred_commands", group.deviceCommands);
                deferredBody.put("deferred_other",    serializeActions(group.otherActions));

                TbMsgMetaData deferMeta = baseMeta.copy();
                deferMeta.putValue("delaySeconds", String.valueOf(group.cumulativeSeconds));

                TbMsg deferMsg = ctx.newMsg(
                        triggerMsg.getQueueName(), "EXECUTE_ACTION",
                        triggerMsg.getOriginator(), triggerMsg.getCustomerId(),
                        deferMeta, MAPPER.writeValueAsString(deferredBody));

                first = emit(ctx, deferMsg, relation, first, log);
                emitted++;
            }
        }
        return emitted;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static TbMsg buildMultiCommandMsg(TbContext ctx, TbMsg original,
                                               TbMsgMetaData baseMeta,
                                               List<Map<String, Object>> commands) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode",     "multi_command");
        body.put("commands", commands);
        TbMsgMetaData meta = baseMeta.copy();
        meta.putValue("actionRuleName", "multi_device_command");
        return ctx.newMsg(original.getQueueName(), "EXECUTE_ACTION",
                original.getOriginator(), original.getCustomerId(),
                meta, MAPPER.writeValueAsString(body));
    }

    private static TbMsg buildActionMsg(TbContext ctx, TbMsg original,
                                         TbMsgMetaData baseMeta, Action action) throws Exception {
        TbMsgMetaData meta = baseMeta.copy();
        meta.putValue("actionRuleName", action.getType());
        return ctx.newMsg(original.getQueueName(), "EXECUTE_ACTION",
                original.getOriginator(), original.getCustomerId(),
                meta, MAPPER.writeValueAsString(action));
    }

    /** Returns updated value of 'first' flag. */
    private static boolean emit(TbContext ctx, TbMsg m, String relation,
                                  boolean first, Logger log) {
        if (first) {
            ctx.tellNext(m, relation);
            return false;
        } else {
            ctx.enqueueForTellNext(m, relation, () -> {}, t ->
                    log.warn("Failed to enqueue action msg: {}", t.getMessage()));
            return false;
        }
    }

    private static List<Map<String, Object>> serializeActions(List<Action> actions) {
        return actions.stream().map(a -> {
            try {
                String json = MAPPER.writeValueAsString(a);
                return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return Map.<String, Object>of("type", a.getType());
            }
        }).collect(Collectors.toList());
    }
}

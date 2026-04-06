package com.smarthome.rule.scene;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.smarthome.rule.util.ActionExecutor;
import com.smarthome.rule.util.AutomationRuleParser;
import com.smarthome.rule.util.AutomationRuleParser.Action;
import com.smarthome.rule.util.AutomationRuleParser.Scene;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.rule.engine.api.*;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SmartHome Scene Executor Node
 *
 * Executes a "Tap to Run" scene stored as the "scenes" server attribute on a Home Asset.
 *
 * Input message:
 *   - originator: Home Asset (the entity whose "scenes" attribute holds the scene list)
 *   - msg body:   { "type": "run_scene", "scene_id": "uuid" }
 *                 OR (from Asset TIMESERIES_UPDATED):
 *                 already normalized by "Normalize Direct Bulk Command" JS transform
 *
 * The node reads the "scenes" attribute from the originator (Home Asset), finds the
 * matching scene by ID, resolves any nested run_scene actions (up to maxRecursionDepth),
 * then fans out via ActionExecutor — same parallel + delay semantics as automation.
 *
 * Output connections:
 *   - "Success" — scene found and at least one action message emitted
 *   - "No Scene" — scene_id not found or scene has no actions
 *   - "Failure"  — JSON parse error or attribute query error
 */
@RuleNode(
        type = ComponentType.ACTION,
        name = "SmartHome Scene Executor",
        configClazz = SmartHomeSceneExecutorConfig.class,
        relationTypes = {"Success", "No Scene", "Failure"},
        nodeDescription = "Executes a SmartHome 'Tap to Run' scene by scene_id",
        nodeDetails = "Reads the 'scenes' server attribute from the Home Asset originator, " +
                "finds the scene by ID, expands run_scene actions recursively, then fans " +
                "out device_command and other actions via ActionExecutor."
)
public class SmartHomeSceneExecutorNode implements TbNode {

    private static final Logger log = LoggerFactory.getLogger(SmartHomeSceneExecutorNode.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SmartHomeSceneExecutorConfig config;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        config = TbNodeUtils.convert(configuration, SmartHomeSceneExecutorConfig.class);
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        // Parse scene_id from msg body
        String sceneId;
        try {
            Map<String, Object> body = MAPPER.readValue(msg.getData(),
                    new TypeReference<Map<String, Object>>() {});
            sceneId = (String) body.get("scene_id");
        } catch (Exception e) {
            log.error("Cannot parse msg body as JSON", e);
            ctx.tellFailure(msg, e);
            return;
        }

        if (sceneId == null || sceneId.isBlank()) {
            ctx.tellFailure(msg, new IllegalArgumentException("msg body missing 'scene_id' field"));
            return;
        }

        // Load "scenes" attribute from the originator (Home Asset)
        EntityId originatorId = msg.getOriginator();
        ListeningExecutor dbExec = ctx.getDbCallbackExecutor();

        ListenableFuture<Optional<AttributeKvEntry>> attrFuture =
                ctx.getAttributesService().find(
                        ctx.getTenantId(), originatorId,
                        AttributeScope.SERVER_SCOPE, config.getScenesAttributeKey());

        final String finalSceneId = sceneId;

        Futures.addCallback(attrFuture, new FutureCallback<Optional<AttributeKvEntry>>() {

            @Override
            public void onSuccess(Optional<AttributeKvEntry> result) {
                if (!result.isPresent() || result.get().getValueAsString() == null) {
                    log.warn("No '{}' attribute on originator {}", config.getScenesAttributeKey(), originatorId);
                    ctx.tellNext(msg, "No Scene");
                    return;
                }

                String scenesJson = result.get().getValueAsString();
                List<Scene> scenes;
                try {
                    scenes = AutomationRuleParser.parseScenes(scenesJson);
                } catch (Exception e) {
                    log.error("Failed to parse scenes JSON", e);
                    ctx.tellFailure(msg, e);
                    return;
                }

                // Build lookup map for recursive run_scene resolution
                Map<String, Scene> sceneMap = scenes.stream()
                        .collect(Collectors.toMap(Scene::getId, s -> s, (a, b) -> a));

                // Find the requested scene
                Scene scene = sceneMap.get(finalSceneId);
                if (scene == null || !scene.isEnabled()) {
                    log.warn("Scene '{}' not found or disabled", finalSceneId);
                    ctx.tellNext(msg, "No Scene");
                    return;
                }

                // Resolve actions (expand run_scene recursively)
                List<Action> resolvedActions;
                try {
                    resolvedActions = resolveActions(scene.getActions(), sceneMap, 0);
                } catch (Exception e) {
                    log.error("Failed to resolve scene actions for scene '{}'", finalSceneId, e);
                    ctx.tellFailure(msg, e);
                    return;
                }

                if (resolvedActions.isEmpty()) {
                    ctx.tellNext(msg, "No Scene");
                    return;
                }

                // Fan out via shared ActionExecutor
                TbMsgMetaData baseMeta = msg.getMetaData().copy();
                baseMeta.putValue("sceneId",        finalSceneId);
                baseMeta.putValue("sceneName",      scene.getName());
                baseMeta.putValue("triggerTimestamp", String.valueOf(System.currentTimeMillis()));

                try {
                    int emitted = ActionExecutor.fanOut(ctx, msg, baseMeta, resolvedActions, "Success", log);
                    if (emitted == 0) {
                        ctx.tellNext(msg, "No Scene");
                    }
                } catch (Exception e) {
                    log.error("Failed to fan out scene '{}' actions", finalSceneId, e);
                    ctx.tellFailure(msg, e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to load '{}' attribute from {}", config.getScenesAttributeKey(), originatorId, t);
                ctx.tellFailure(msg, t);
            }

        }, dbExec);
    }

    /**
     * Recursively resolve run_scene actions, replacing them with the target scene's actions.
     * Delay actions from the outer sequence are preserved; delay actions inside a nested scene
     * are also preserved (they are simply interleaved at the expansion point).
     * Cycles and over-deep recursion are guarded by maxDepth.
     */
    private List<Action> resolveActions(List<Action> actions, Map<String, Scene> sceneMap, int depth) {
        if (actions == null || actions.isEmpty()) return Collections.emptyList();
        if (depth > config.getMaxRecursionDepth()) {
            log.warn("Scene recursion depth {} exceeded max {}, stopping expansion", depth, config.getMaxRecursionDepth());
            return Collections.emptyList();
        }

        List<Action> resolved = new ArrayList<>();
        for (Action action : actions) {
            if ("run_scene".equals(action.getType()) && action.getSceneId() != null) {
                Scene nested = sceneMap.get(action.getSceneId());
                if (nested != null && nested.isEnabled()) {
                    List<Action> nestedActions = resolveActions(nested.getActions(), sceneMap, depth + 1);
                    resolved.addAll(nestedActions);
                } else {
                    log.warn("run_scene references unknown/disabled scene '{}', skipping", action.getSceneId());
                }
            } else {
                resolved.add(action);
            }
        }
        return resolved;
    }

    @Override
    public void destroy() {}
}

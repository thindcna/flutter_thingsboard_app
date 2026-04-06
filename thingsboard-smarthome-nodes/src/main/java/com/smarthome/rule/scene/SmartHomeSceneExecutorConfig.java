package com.smarthome.rule.scene;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

/**
 * Configuration for {@link SmartHomeSceneExecutorNode}.
 */
@Data
public class SmartHomeSceneExecutorConfig implements NodeConfiguration<SmartHomeSceneExecutorConfig> {

    /**
     * Server attribute key on the Home Asset that holds the scenes JSON array.
     * Default: "scenes"
     */
    private String scenesAttributeKey = "scenes";

    /**
     * Maximum recursion depth for run_scene actions (scene calling another scene).
     * Prevents infinite loops. Default: 3
     */
    private int maxRecursionDepth = 3;

    @Override
    public SmartHomeSceneExecutorConfig defaultConfiguration() {
        return new SmartHomeSceneExecutorConfig();
    }
}

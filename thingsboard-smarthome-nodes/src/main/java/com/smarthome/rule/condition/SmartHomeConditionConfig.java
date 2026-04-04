package com.smarthome.rule.condition;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

/**
 * Configuration for {@link SmartHomeConditionNode}.
 * Exposed as form fields in the ThingsBoard rule chain UI.
 */
@Data
public class SmartHomeConditionConfig implements NodeConfiguration<SmartHomeConditionConfig> {

    /**
     * Server attribute key on the Home Asset that holds the JSON automation rules array.
     * Default: "automations"
     */
    private String automationsAttributeKey = "automations";

    /**
     * Attribute scope to read from the Home Asset.
     * Default: SERVER_SCOPE
     */
    private String attributeScope = "SERVER_SCOPE";

    /**
     * How old (ms) the latest telemetry of a referenced device can be
     * before its state is considered stale and that condition is skipped.
     * Default: 300000 (5 minutes)
     */
    private long deviceStateStalenesMs = 300_000L;

    /**
     * Timeout (ms) for the async DB query to TimeseriesService.findLatest.
     * If the query takes longer, a Failure is emitted.
     * Default: 5000
     */
    private long queryTimeoutMs = 5_000L;

    @Override
    public SmartHomeConditionConfig defaultConfiguration() {
        return new SmartHomeConditionConfig();
    }
}

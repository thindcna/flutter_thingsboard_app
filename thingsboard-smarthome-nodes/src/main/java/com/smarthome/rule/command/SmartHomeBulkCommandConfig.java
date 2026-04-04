package com.smarthome.rule.command;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

/**
 * Configuration for {@link SmartHomeBulkCommandNode}.
 * Exposed as form fields in the ThingsBoard rule chain UI.
 */
@Data
public class SmartHomeBulkCommandConfig implements NodeConfiguration<SmartHomeBulkCommandConfig> {

    /**
     * Timeout (ms) for each individual device RPC call.
     * If a device does not respond in time, it is counted as "failed".
     * Default: 10000
     */
    private int rpcTimeoutMs = 10_000;

    /**
     * PARALLEL — send RPC to all devices simultaneously (Futures.allAsList).
     * SEQUENTIAL — send one by one, wait for response before next.
     * Default: PARALLEL
     */
    private ExecutionMode executionMode = ExecutionMode.PARALLEL;

    /**
     * When true: if some devices fail, continue and emit "Partial Success".
     * When false: if any device fails, immediately emit "Failure".
     * Default: true
     */
    private boolean continueOnPartialFailure = true;

    /**
     * Relation type string used to find devices in room/home assets.
     * Must match the relation type used in the ThingsBoard asset hierarchy.
     * Default: "Contains"
     */
    private String relationTypeFilter = "Contains";

    /**
     * Timeout (ms) for the RelationService query when resolving room/home devices.
     * Default: 3000
     */
    private long relationQueryTimeoutMs = 3_000L;

    @Override
    public SmartHomeBulkCommandConfig defaultConfiguration() {
        return new SmartHomeBulkCommandConfig();
    }

    public enum ExecutionMode {
        PARALLEL,
        SEQUENTIAL
    }
}

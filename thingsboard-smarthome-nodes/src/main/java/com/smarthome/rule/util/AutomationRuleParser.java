package com.smarthome.rule.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Parses the SmartHome automation rule JSON format stored in
 * the Home Asset's "automations" server attribute.
 *
 * Expected format (see DATA_MODEL_REFERENCE.md):
 * [
 *   {
 *     "id": "uuid",
 *     "name": "...",
 *     "enabled": true,
 *     "condition_match": "all"|"any",
 *     "execution_target": "server",
 *     "conditions": [ {...}, ... ],
 *     "actions":    [ {...}, ... ],
 *     "extra": { "trigger_once": false, "effective_period": {...} }
 *   }
 * ]
 */
public class AutomationRuleParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parse a JSON string (the value of "automations" attribute) into a list of rules. */
    public static List<AutomationRule> parseRules(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(json, new TypeReference<List<AutomationRule>>() {});
    }

    /** Parse a JSON string (the value of "scenes" attribute) into a list of scenes. */
    public static List<Scene> parseScenes(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(json, new TypeReference<List<Scene>>() {});
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Domain model
    // ─────────────────────────────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scene {
        private String id;
        private String name;
        private String icon;
        private String color;
        private boolean enabled = true;
        private List<Action> actions;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AutomationRule {
        private String id;
        private String name;
        private boolean enabled = true;

        @JsonProperty("condition_match")
        private String conditionMatch = "all";          // "all" | "any"

        @JsonProperty("execution_target")
        private String executionTarget = "server";

        private List<Condition> conditions;
        private List<Action>    actions;
        private Extra           extra;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Condition {
        private String type;                            // "device_state" | "time_range" | ...

        // device_state
        @JsonProperty("device_id")
        private String deviceId;
        private String attribute;
        private String operator;                        // ">" | "<" | "==" | "!=" | ">=" | "<="
        private Object value;                          // can be number or string

        // time_range
        private String from;                           // "HH:mm"
        private String to;                             // "HH:mm"

        // time_schedule
        private String cron;

        // day_of_week
        private List<Integer> days;                    // 1=Mon … 7=Sun

        // sunrise_sunset
        private String event;                          // "sunrise" | "sunset"
        @JsonProperty("offset_minutes")
        private int offsetMinutes;
        private double latitude;
        private double longitude;

        // device_offline
        @JsonProperty("duration_seconds")
        private long durationSeconds;

        // weather
        private String value2;                         // alias for polymorphic "value" if needed
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Action {
        private String type;                           // "device_command" | "delay" | ...

        // device_command
        @JsonProperty("device_id")
        private String deviceId;
        @JsonProperty("device_name")
        private String deviceName;                     // REQUIRED — exact name in ThingsBoard
        private String command;
        private Map<String, Object> params;

        // delay
        private int seconds;

        // run_scene
        @JsonProperty("scene_id")
        private String sceneId;

        // send_notification
        private String message;
        private String target;

        // enable_automation
        @JsonProperty("automation_id")
        private String automationId;
        private boolean enabledValue;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Extra {
        @JsonProperty("trigger_once")
        private boolean triggerOnce = false;
        @JsonProperty("effective_period")
        private Map<String, Object> effectivePeriod;
    }
}

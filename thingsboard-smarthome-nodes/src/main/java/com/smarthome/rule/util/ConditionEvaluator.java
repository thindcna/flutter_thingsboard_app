package com.smarthome.rule.util;

import com.smarthome.rule.util.AutomationRuleParser.AutomationRule;
import com.smarthome.rule.util.AutomationRuleParser.Condition;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Evaluates automation rule conditions against a snapshot of device telemetry.
 *
 * Usage:
 *   Map<DeviceId, Map<String, TsKvEntry>> snapshot = ...;  // from TimeseriesService.findLatest
 *   ConditionEvaluator.Result result = ConditionEvaluator.evaluate(rule, snapshot, triggerDeviceId,
 *       triggerData, stalenesMs);
 */
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    public static final class Result {
        public final boolean matched;
        public final String reason;

        private Result(boolean matched, String reason) {
            this.matched = matched;
            this.reason = reason;
        }
    }

    /**
     * Evaluate all conditions in a rule.
     *
     * @param rule           The automation rule to check
     * @param snapshot       Latest telemetry per device (from TimeseriesService.findLatest)
     * @param triggerDeviceId UUID of the device that sent the current message (may be in snapshot too)
     * @param triggerData    Current message body values for the trigger device
     * @param stalenesMs     Max age of device state before it is considered stale and condition is skipped
     */
    public static Result evaluate(
            AutomationRule rule,
            Map<DeviceId, Map<String, TsKvEntry>> snapshot,
            UUID triggerDeviceId,
            Map<String, Object> triggerData,
            long stalenesMs) {

        if (!rule.isEnabled()) {
            return new Result(false, "rule disabled");
        }

        List<Condition> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return new Result(false, "no conditions");
        }

        boolean matchAll = !"any".equalsIgnoreCase(rule.getConditionMatch());

        for (Condition c : conditions) {
            boolean condResult = evaluateOne(c, snapshot, triggerDeviceId, triggerData, stalenesMs);
            if (matchAll && !condResult) {
                return new Result(false, "condition not met: " + c.getType());
            }
            if (!matchAll && condResult) {
                return new Result(true, "at least one condition met");
            }
        }

        // matchAll=true → all passed. matchAll=false (any) → none passed.
        return new Result(matchAll, matchAll ? "all conditions met" : "no condition met");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static boolean evaluateOne(
            Condition c,
            Map<DeviceId, Map<String, TsKvEntry>> snapshot,
            UUID triggerDeviceId,
            Map<String, Object> triggerData,
            long stalenesMs) {

        try {
            return switch (c.getType()) {
                case "device_state"  -> evaluateDeviceState(c, snapshot, triggerDeviceId, triggerData, stalenesMs);
                case "time_range"    -> evaluateTimeRange(c);
                case "day_of_week"   -> evaluateDayOfWeek(c);
                case "time_schedule" -> evaluateTimeSchedule(c);
                case "device_offline"-> evaluateDeviceOffline(c, snapshot, stalenesMs);
                default -> {
                    log.warn("Unknown condition type: {}", c.getType());
                    yield false;
                }
            };
        } catch (Exception e) {
            log.warn("Error evaluating condition type={}: {}", c.getType(), e.getMessage());
            return false;
        }
    }

    // ─── device_state ─────────────────────────────────────────────────────────

    private static boolean evaluateDeviceState(
            Condition c,
            Map<DeviceId, Map<String, TsKvEntry>> snapshot,
            UUID triggerDeviceId,
            Map<String, Object> triggerData,
            long stalenesMs) {

        if (c.getDeviceId() == null || c.getAttribute() == null) return false;

        UUID deviceUuid = UUID.fromString(c.getDeviceId());
        Object actualValue;
        long valueTs = System.currentTimeMillis();

        // Use live trigger data for the triggering device
        if (deviceUuid.equals(triggerDeviceId) && triggerData.containsKey(c.getAttribute())) {
            actualValue = triggerData.get(c.getAttribute());
        } else {
            // Look up from DB snapshot
            DeviceId deviceId = new DeviceId(deviceUuid);
            Map<String, TsKvEntry> deviceState = snapshot.get(deviceId);
            if (deviceState == null) {
                log.debug("No telemetry snapshot for device {}", c.getDeviceId());
                return false;
            }
            TsKvEntry entry = deviceState.get(c.getAttribute());
            if (entry == null) {
                log.debug("No key '{}' for device {}", c.getAttribute(), c.getDeviceId());
                return false;
            }
            valueTs = entry.getTs();
            // Check staleness
            if (System.currentTimeMillis() - valueTs > stalenesMs) {
                log.debug("Stale telemetry for device {}, key {}, age={}ms",
                        c.getDeviceId(), c.getAttribute(), System.currentTimeMillis() - valueTs);
                return false;
            }
            actualValue = kvEntryValue(entry);
        }

        return compareValues(actualValue, c.getOperator(), c.getValue());
    }

    private static Object kvEntryValue(TsKvEntry entry) {
        return switch (entry.getDataType()) {
            case LONG    -> entry.getLongValue().orElse(null);
            case DOUBLE  -> entry.getDoubleValue().orElse(null);
            case BOOLEAN -> entry.getBooleanValue().orElse(null);
            default      -> entry.getStrValue().orElse(null);
        };
    }

    /**
     * Compare actual value (from device) against threshold value (from rule).
     * Supports: >, <, >=, <=, ==, !=
     */
    private static boolean compareValues(Object actual, String operator, Object threshold) {
        if (actual == null || operator == null || threshold == null) return false;

        // Numeric comparison
        if (isNumeric(actual) && isNumeric(threshold)) {
            double a = toDouble(actual);
            double t = toDouble(threshold);
            return switch (operator) {
                case ">"  -> a > t;
                case "<"  -> a < t;
                case ">=" -> a >= t;
                case "<=" -> a <= t;
                case "==" -> a == t;
                case "!=" -> a != t;
                default -> false;
            };
        }

        // Boolean comparison
        if (actual instanceof Boolean actualBool) {
            boolean t = Boolean.parseBoolean(threshold.toString());
            return switch (operator) {
                case "==" -> actualBool == t;
                case "!=" -> actualBool != t;
                default   -> false;
            };
        }

        // String comparison
        String a = actual.toString();
        String t = threshold.toString();
        return switch (operator) {
            case "==" -> a.equals(t);
            case "!=" -> !a.equals(t);
            default   -> false;
        };
    }

    // ─── time_range ───────────────────────────────────────────────────────────

    private static boolean evaluateTimeRange(Condition c) {
        if (c.getFrom() == null || c.getTo() == null) return false;
        LocalTime now  = LocalTime.now(ZoneOffset.UTC);
        LocalTime from = LocalTime.parse(c.getFrom());
        LocalTime to   = LocalTime.parse(c.getTo());

        if (from.isBefore(to)) {
            return !now.isBefore(from) && !now.isAfter(to);
        } else {
            // Overnight range e.g. 22:00 – 06:00
            return !now.isBefore(from) || !now.isAfter(to);
        }
    }

    // ─── day_of_week ──────────────────────────────────────────────────────────

    private static boolean evaluateDayOfWeek(Condition c) {
        if (c.getDays() == null || c.getDays().isEmpty()) return false;
        // Rule uses 1=Mon … 7=Sun; Java DayOfWeek.getValue() is same
        int today = ZonedDateTime.now(ZoneOffset.UTC).getDayOfWeek().getValue();
        return c.getDays().contains(today);
    }

    // ─── time_schedule (cron expression) ─────────────────────────────────────

    /**
     * Minimal cron match: checks if "now" falls within the last minute that
     * the cron expression would have fired. For production, consider Quartz CronExpression.
     * This is a placeholder — integrate a cron library if needed.
     */
    private static boolean evaluateTimeSchedule(Condition c) {
        // TODO: use Quartz CronExpression or Spring CronExpression to evaluate
        // e.g. CronExpression.parse(c.getCron()).matches(LocalDateTime.now(ZoneOffset.UTC))
        log.warn("time_schedule condition not fully implemented — returning false. Cron: {}", c.getCron());
        return false;
    }

    // ─── device_offline ───────────────────────────────────────────────────────

    private static boolean evaluateDeviceOffline(
            Condition c,
            Map<DeviceId, Map<String, TsKvEntry>> snapshot,
            long stalenesMs) {

        if (c.getDeviceId() == null) return false;
        DeviceId deviceId = new DeviceId(UUID.fromString(c.getDeviceId()));
        Map<String, TsKvEntry> deviceState = snapshot.get(deviceId);

        if (deviceState == null || deviceState.isEmpty()) {
            // No telemetry at all → assume offline
            return true;
        }

        // Find the most recent timestamp across all keys
        long lastSeenMs = deviceState.values().stream()
                .mapToLong(TsKvEntry::getTs)
                .max()
                .orElse(0L);

        long offlineDurationMs = c.getDurationSeconds() * 1000L;
        long actualOfflineMs   = System.currentTimeMillis() - lastSeenMs;
        return actualOfflineMs >= offlineDurationMs;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static boolean isNumeric(Object v) {
        return v instanceof Number;
    }

    private static double toDouble(Object v) {
        return ((Number) v).doubleValue();
    }
}

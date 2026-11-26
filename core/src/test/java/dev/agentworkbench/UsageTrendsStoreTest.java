package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定原始请求时间和身份，验证持久趋势不受明细分页、当前路由或运行汇总影响。
 */
class UsageTrendsStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-26T13:14:15Z");
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private ExecutionStore open() throws Exception { return new ExecutionStore(mapper, directory.toString()); }

    private void identities(ExecutionStore store) {
        store.put("project", obj("id", "one")); store.put("project", obj("id", "two"));
        store.put("session", obj("id", "s1", "projectId", "one")); store.put("session", obj("id", "s2", "projectId", "one"));
        store.put("session", obj("id", "other-session", "projectId", "two"));
        store.put("task", obj("id", "t1", "projectId", "one", "sessionId", "s1"));
        store.put("task", obj("id", "t2", "projectId", "one", "sessionId", "s2"));
        store.put("task", obj("id", "other-task", "projectId", "two", "sessionId", "other-session"));
    }

    private Map<String, Object> request(String id, String project, String session, String task, String at, String unit, String state, Map<String, Object> usage) {
        return obj("id", id, "projectId", project, "sessionId", session, "taskId", task, "createdAt", at, "unit", unit, "state", state, "usage", usage,
            "inputPreview", "private request prompt", "outputPreview", "private result", "providerRequestId", "private-provider-identity");
    }

    private void add(ExecutionStore store, String id, String at, String unit, String state, Map<String, Object> usage) {
        store.put("modelRequest", request(id, "one", "s1", "t1", at, unit, state, usage));
    }

    private Map<String, Object> trend(ExecutionStore store, int days) { return store.usageTrends("one", days, "", "", NOW); }
    private Map<String, Object> summary(Map<String, Object> trend) { return map(trend, "summary"); }
    private Map<String, Object> unit(Map<String, Object> aggregate, String unit) { return map(map(aggregate, "units"), unit); }
    private Map<String, Object> metric(Map<String, Object> aggregate, String unit, String field) { return map(map(unit(aggregate, unit), "tokens"), field); }
    private Map<String, Object> bucket(Map<String, Object> trend, String date) { return objects(trend.get("buckets")).stream().filter(item -> date.equals(str(item, "date"))).findFirst().orElseThrow(); }
    private long count(Map<String, Object> object, String name) { return ((Number) object.get(name)).longValue(); }

    private void assertMetric(Map<String, Object> value, long observed, long known, long unknown, Object total) {
        assertEquals(observed, count(value, "observedSum")); assertEquals(known, count(value, "knownCount"));
        assertEquals(unknown, count(value, "unknownCount"));
        if (total instanceof Number number) assertEquals(number.longValue(), count(value, "total")); else assertEquals(total, value.get("total"));
        assertEquals(false, value.get("overflow"));
    }

    @Test
    void moreThanOneRequestPageIsAggregatedExactlyAndSurvivesReopen() throws Exception {
        Map<String, Object> before;
        try (var store = open()) {
            identities(store);
            List<Map<String, Object>> requests = new ArrayList<>();
            for (int index = 0; index < 257; index++) requests.add(request("many-" + index, "one", "s1", "t1", "2026-11-26T01:00:00Z", "API_REQUEST", "SUCCEEDED",
                obj("inputTokens", 1, "outputTokens", 2, "cachedInputTokens", 1, "reasoningTokens", 1)));
            store.commitRecords(Map.of("modelRequest", requests), "t1", List.of());
            store.put("agentRun", obj("id", "duplicate-run", "projectId", "one", "createdAt", NOW.toString(), "usage", obj("inputTokens", 999999, "outputTokens", 999999)));
            assertEquals(100, objects(store.modelRequestPage("t1", "", 0, 1000, "", "").get("requests")).size());
            before = trend(store, 7); assertEquals(257, count(summary(before), "recordCount"));
            assertMetric(metric(summary(before), "API_REQUEST", "inputTokens"), 257, 257, 0, 257L);
            assertMetric(metric(summary(before), "API_REQUEST", "outputTokens"), 514, 257, 0, 514L);
            assertMetric(metric(summary(before), "API_REQUEST", "cachedInputTokens"), 257, 257, 0, 257L);
            assertMetric(metric(summary(before), "API_REQUEST", "reasoningTokens"), 257, 257, 0, 257L);
            assertEquals(7, objects(before.get("buckets")).size()); assertEquals("UNKNOWN", before.get("cost")); assertEquals("UNKNOWN", before.get("cliInternalRequests"));
            String json = mapper.writeValueAsString(before); assertFalse(json.contains("private request")); assertFalse(json.contains("private-provider")); assertFalse(json.contains("duplicate-run"));
            try (var check = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var statement = check.createStatement();
                 var rows = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='index' AND name='entities_model_request_project_created'")) {
                assertTrue(rows.next()); assertTrue(rows.getString(1).contains("WHERE kind='modelRequest'")); assertTrue(rows.getString(1).contains("$.createdAt"));
            }
        }
        try (var store = open()) { assertEquals(before, trend(store, 7)); }
    }

    @Test
    void missingValuesStayUnknownWhileZeroAndUnitsRetainTheirOwnMeaning() throws Exception {
        try (var store = open()) {
            identities(store);
            add(store, "api-one", NOW.toString(), "API_REQUEST", "SUCCEEDED", obj("inputTokens", 100, "outputTokens", 20, "cachedInputTokens", 40, "reasoningTokens", 5));
            add(store, "api-two", NOW.toString(), "API_REQUEST", "FAILED", obj("inputTokens", 0, "outputTokens", "UNKNOWN", "cachedInputTokens", 0, "reasoningTokens", "UNKNOWN"));
            add(store, "cli", NOW.toString(), "CLI_SESSION", "CANCELLED", obj("modelRequests", "UNKNOWN", "inputTokens", "UNKNOWN", "outputTokens", "UNKNOWN"));
            add(store, "legacy-unit", NOW.toString(), "unrecognized", "legacy-state", obj("inputTokens", 0));
            Map<String, Object> sum = summary(trend(store, 7));
            assertEquals(4, count(sum, "recordCount")); assertEquals(true, sum.get("hasRecords"));
            assertEquals(2, count(unit(sum, "API_REQUEST"), "recordCount")); assertEquals(1, count(unit(sum, "CLI_SESSION"), "recordCount"));
            assertEquals(1, count(unit(sum, "UNKNOWN"), "recordCount"));
            assertMetric(metric(sum, "API_REQUEST", "inputTokens"), 100, 2, 0, 100L);
            assertMetric(metric(sum, "API_REQUEST", "outputTokens"), 20, 1, 1, "UNKNOWN");
            assertMetric(metric(sum, "API_REQUEST", "cachedInputTokens"), 40, 2, 0, 40L);
            assertMetric(metric(sum, "API_REQUEST", "reasoningTokens"), 5, 1, 1, "UNKNOWN");
            assertMetric(metric(sum, "CLI_SESSION", "inputTokens"), 0, 0, 1, "UNKNOWN");
            assertMetric(metric(sum, "UNKNOWN", "inputTokens"), 0, 1, 0, 0L);
            assertEquals(1, count(map(unit(sum, "API_REQUEST"), "states"), "SUCCEEDED"));
            assertEquals(1, count(map(unit(sum, "API_REQUEST"), "states"), "FAILED"));
            assertEquals(1, count(map(unit(sum, "CLI_SESSION"), "states"), "CANCELLED"));
            assertEquals(1, count(map(unit(sum, "UNKNOWN"), "states"), "UNKNOWN"));
        }
    }

    @Test
    void missingUnitOrStateStaysInTheUnknownColumnsInsteadOfBecomingAnApiRequest() throws Exception {
        try (var store = open()) {
            identities(store);
            Map<String, Object> missing = request("missing", "one", "s1", "t1", NOW.toString(), "API_REQUEST", "SUCCEEDED", obj("inputTokens", 0));
            missing.remove("unit"); missing.remove("state"); store.put("modelRequest", missing);
            Map<String, Object> sum = summary(trend(store, 7));
            assertEquals(1, count(unit(sum, "UNKNOWN"), "recordCount")); assertEquals(0, count(unit(sum, "API_REQUEST"), "recordCount"));
            assertEquals(1, count(map(unit(sum, "UNKNOWN"), "states"), "UNKNOWN"));
            assertMetric(metric(sum, "UNKNOWN", "inputTokens"), 0, 1, 0, 0L);
        }
    }

    @Test
    void utcBoundariesIncludeTodayAndFillEmptyBucketsWithoutPretendingKnownZero() throws Exception {
        try (var store = open()) {
            identities(store);
            add(store, "before", "2026-11-19T23:59:59.999999999Z", "API_REQUEST", "SUCCEEDED", obj("inputTokens", 999));
            add(store, "first", "2026-11-20T00:00:00Z", "API_REQUEST", "SUCCEEDED", obj("inputTokens", 1));
            add(store, "last", "2026-11-26T23:59:59.999999999Z", "API_REQUEST", "SUCCEEDED", obj("inputTokens", 2));
            add(store, "tomorrow", "2026-11-27T00:00:00Z", "API_REQUEST", "SUCCEEDED", obj("inputTokens", 999));
            add(store, "offset-inside", "2026-11-19T20:00:00-04:00", "API_REQUEST", "SUCCEEDED", obj("inputTokens", 4));
            add(store, "offset-outside", "2026-11-20T00:00:00+01:00", "API_REQUEST", "SUCCEEDED", obj("inputTokens", 999));
            add(store, "invalid-date", "not-a-time", "API_REQUEST", "SUCCEEDED", obj("inputTokens", 999));
            Map<String, Object> result = trend(store, 7);
            assertEquals("2026-11-20T00:00:00Z", result.get("from")); assertEquals("2026-11-27T00:00:00Z", result.get("toExclusive"));
            assertEquals("UTC", result.get("timeZone")); assertEquals(3, count(summary(result), "recordCount"));
            assertMetric(metric(summary(result), "API_REQUEST", "inputTokens"), 7, 3, 0, 7L);
            assertEquals(2, count(bucket(result, "2026-11-20"), "recordCount")); assertEquals(1, count(bucket(result, "2026-11-26"), "recordCount"));
            Map<String, Object> empty = bucket(result, "2026-11-21"); assertEquals(false, empty.get("hasRecords")); assertEquals(0, count(empty, "recordCount"));
            assertMetric(metric(empty, "API_REQUEST", "inputTokens"), 0, 0, 0, "UNKNOWN");
            for (int days : List.of(7, 30, 90)) {
                Map<String, Object> range = trend(store, days); assertEquals(days, objects(range.get("buckets")).size());
                LocalDate expected = NOW.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(days - 1L);
                assertEquals(expected.toString(), objects(range.get("buckets")).getFirst().get("date"));
                assertEquals("2026-11-26", objects(range.get("buckets")).getLast().get("date"));
            }
        }
    }

    @Test
    void projectSessionAndTaskScopesUseOriginalRecordIdentityAndRejectForeignSelectors() throws Exception {
        try (var store = open()) {
            identities(store);
            add(store, "one", NOW.toString(), "API_REQUEST", "SUCCEEDED", obj("inputTokens", 1));
            store.put("modelRequest", request("two", "one", "s2", "t2", NOW.toString(), "API_REQUEST", "SUCCEEDED", obj("inputTokens", 2)));
            store.put("modelRequest", request("foreign", "two", "other-session", "other-task", NOW.toString(), "API_REQUEST", "SUCCEEDED", obj("inputTokens", 999)));
            assertEquals(2, count(summary(trend(store, 7)), "recordCount"));
            assertEquals(1, count(summary(store.usageTrends("one", 7, "s1", "", NOW)), "recordCount"));
            assertEquals(1, count(summary(store.usageTrends("one", 7, "", "t2", NOW)), "recordCount"));
            assertMetric(metric(summary(store.usageTrends("one", 7, "s2", "t2", NOW)), "API_REQUEST", "inputTokens"), 2, 1, 0, 2L);
            for (String project : List.of("missing")) assertEquals(404, assertThrows(ApiException.class, () -> store.usageTrends(project, 7, "", "", NOW)).status());
            for (String session : List.of("missing", "other-session")) assertEquals(404, assertThrows(ApiException.class, () -> store.usageTrends("one", 7, session, "", NOW)).status());
            for (String task : List.of("missing", "other-task", "t2")) assertEquals(404, assertThrows(ApiException.class, () -> store.usageTrends("one", 7, "s1", task, NOW)).status());
            assertTrue(store.healthy());
            store.put("task", obj("id", "t1", "projectId", "one", "sessionId", "s2"));
            assertMetric(metric(summary(store.usageTrends("one", 7, "s1", "", NOW)), "API_REQUEST", "inputTokens"), 1, 1, 0, 1L);
            assertMetric(metric(summary(store.usageTrends("one", 7, "", "t1", NOW)), "API_REQUEST", "inputTokens"), 1, 1, 0, 1L);
        }
    }

    @Test
    void dispatchingToTerminalUpdatesOneOriginalBucketAndSummaryMatchesAllBuckets() throws Exception {
        try (var store = open()) {
            identities(store);
            add(store, "pending", "2026-11-25T23:59:59Z", "API_REQUEST", "DISPATCHING", obj());
            Map<String, Object> pending = trend(store, 7);
            assertEquals(1, count(map(unit(summary(pending), "API_REQUEST"), "states"), "DISPATCHING"));
            assertMetric(metric(summary(pending), "API_REQUEST", "inputTokens"), 0, 0, 1, "UNKNOWN");
            store.finishModelRequest("pending", obj("state", "SUCCEEDED", "usage", obj("inputTokens", 7, "outputTokens", 3)));
            Map<String, Object> finished = trend(store, 7);
            assertEquals(1, count(summary(finished), "recordCount")); assertEquals(1, count(bucket(finished, "2026-11-25"), "recordCount"));
            assertEquals(0, count(bucket(finished, "2026-11-26"), "recordCount"));
            assertEquals(0, count(map(unit(summary(finished), "API_REQUEST"), "states"), "DISPATCHING"));
            assertMetric(metric(summary(finished), "API_REQUEST", "inputTokens"), 7, 1, 0, 7L);
            assertEquals(1, store.list("modelRequest").size());
            for (String unit : List.of("API_REQUEST", "CLI_SESSION", "UNKNOWN")) {
                long bucketRecords = objects(finished.get("buckets")).stream().mapToLong(item -> count(unit(item, unit), "recordCount")).sum();
                assertEquals(bucketRecords, count(unit(summary(finished), unit), "recordCount"));
                for (String field : List.of("inputTokens", "outputTokens", "cachedInputTokens", "reasoningTokens")) {
                    long known = objects(finished.get("buckets")).stream().mapToLong(item -> count(metric(item, unit, field), "knownCount")).sum();
                    long unknown = objects(finished.get("buckets")).stream().mapToLong(item -> count(metric(item, unit, field), "unknownCount")).sum();
                    assertEquals(known, count(metric(summary(finished), unit, field), "knownCount")); assertEquals(unknown, count(metric(summary(finished), unit, field), "unknownCount"));
                }
            }
        }
    }

    @Test
    void malformedTokensAreUnknownAndExactSumsNeverOverflowSqliteOrJsonIntegers() throws Exception {
        try (var store = open()) {
            identities(store);
            List<Object> invalid = List.of(-1, 1.5, 1.0, "0", true, obj("value", 3), List.of(1));
            int index = 0;
            for (Object value : invalid) add(store, "invalid-" + index++, NOW.toString(), "API_REQUEST", "SUCCEEDED", obj("inputTokens", value));
            add(store, "null", NOW.toString(), "API_REQUEST", "SUCCEEDED", obj("inputTokens", null));
            add(store, "missing", NOW.toString(), "API_REQUEST", "SUCCEEDED", obj());
            add(store, "zero", NOW.toString(), "API_REQUEST", "SUCCEEDED", obj("inputTokens", 0));
            assertMetric(metric(summary(trend(store, 7)), "API_REQUEST", "inputTokens"), 0, 1, 9, "UNKNOWN");
            add(store, "large-one", "2026-11-25T00:00:00Z", "CLI_SESSION", "SUCCEEDED", obj("inputTokens", Long.MAX_VALUE));
            add(store, "large-two", "2026-11-25T00:00:00Z", "CLI_SESSION", "SUCCEEDED", obj("inputTokens", Long.MAX_VALUE));
            add(store, "day-one", "2026-11-24T00:00:00Z", "UNKNOWN", "SUCCEEDED", obj("inputTokens", 5_000_000_000_000_000L));
            add(store, "day-two", "2026-11-25T00:00:00Z", "UNKNOWN", "SUCCEEDED", obj("inputTokens", 5_000_000_000_000_000L));
            Map<String, Object> result = trend(store, 7);
            for (String unit : List.of("CLI_SESSION", "UNKNOWN")) {
                Map<String, Object> value = metric(summary(result), unit, "inputTokens");
                assertEquals("UNKNOWN", value.get("total")); assertEquals("UNKNOWN", value.get("observedSum")); assertEquals(true, value.get("overflow"));
                assertEquals(2, count(value, "knownCount")); assertEquals(0, count(value, "unknownCount"));
            }
            assertMetric(metric(bucket(result, "2026-11-24"), "UNKNOWN", "inputTokens"), 5_000_000_000_000_000L, 1, 0, 5_000_000_000_000_000L);
            assertTrue(store.healthy());
        }
    }

    @Test
    void jsonSafeIntegerBoundaryAndEachTokenFieldAreIndependent() throws Exception {
        try (var store = open()) {
            identities(store);
            long maximum = 9_007_199_254_740_991L;
            add(store, "boundary", NOW.toString(), "API_REQUEST", "SUCCEEDED",
                obj("inputTokens", maximum, "outputTokens", maximum, "cachedInputTokens", 0, "reasoningTokens", 0));
            assertMetric(metric(summary(trend(store, 7)), "API_REQUEST", "inputTokens"), maximum, 1, 0, maximum);
            add(store, "plus-one", NOW.toString(), "API_REQUEST", "SUCCEEDED",
                obj("inputTokens", 1, "outputTokens", 0, "cachedInputTokens", 0, "reasoningTokens", "UNKNOWN"));
            Map<String, Object> sum = summary(trend(store, 7)), overflow = metric(sum, "API_REQUEST", "inputTokens");
            assertEquals(true, overflow.get("overflow")); assertEquals("UNKNOWN", overflow.get("total")); assertEquals("UNKNOWN", overflow.get("observedSum"));
            assertEquals(2, count(overflow, "knownCount")); assertEquals(0, count(overflow, "unknownCount"));
            assertMetric(metric(sum, "API_REQUEST", "outputTokens"), maximum, 2, 0, maximum);
            assertMetric(metric(sum, "API_REQUEST", "cachedInputTokens"), 0, 2, 0, 0L);
            assertMetric(metric(sum, "API_REQUEST", "reasoningTokens"), 0, 1, 1, "UNKNOWN");
            assertTrue(store.healthy());
        }
    }

    @Test
    void invalidDaysAreClientErrorsAndAnEmptyProjectHasNoRecords() throws Exception {
        try (var store = open()) {
            identities(store);
            for (int days : List.of(-1, 0, 1, 8, 29, 31, 91, Integer.MAX_VALUE)) assertEquals(400, assertThrows(ApiException.class, () -> trend(store, days)).status());
            Map<String, Object> empty = trend(store, 7); assertEquals(false, summary(empty).get("hasRecords")); assertEquals(0, count(summary(empty), "recordCount"));
            for (Map<String, Object> bucket : objects(empty.get("buckets"))) {
                assertEquals(false, bucket.get("hasRecords"));
                for (String unit : List.of("API_REQUEST", "CLI_SESSION", "UNKNOWN")) assertMetric(metric(bucket, unit, "inputTokens"), 0, 0, 0, "UNKNOWN");
            }
            assertTrue(store.healthy());
        }
    }
}

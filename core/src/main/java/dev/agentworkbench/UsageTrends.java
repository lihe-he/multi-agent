package dev.agentworkbench;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.sqlite.Function;
import static dev.agentworkbench.Json.*;

/**
 * 仅聚合持久请求；按 UTC 原始时间分桶，并保留缺失用量与明确零的区别。
 */
final class UsageTrends {
    private static final List<String> UNITS = List.of("API_REQUEST", "CLI_SESSION", "UNKNOWN");
    private static final List<String> STATES = List.of("DISPATCHING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");
    private static final List<String> FIELDS = List.of("inputTokens", "outputTokens", "cachedInputTokens", "reasoningTokens");
    private static final BigInteger MAX_JSON_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);

    private UsageTrends() { }

    /**
     * SQLite 整数 SUM 可能溢出；精确十进制聚合只向查询返回有界分组结果。
     */
    static void install(Connection connection) throws SQLException {
        Function.create(connection, "usage_trends_sum", new ExactSum(), 1, Function.FLAG_DETERMINISTIC);
        Function.create(connection, "usage_trends_utc_day", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                String value = value_text(0);
                if (value == null) { result(); return; }
                try { result(Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDate().toString()); }
                catch (java.time.DateTimeException exception) { result(); }
            }
        }, 1, Function.FLAG_DETERMINISTIC);
    }

    static void requireDays(int days) {
        if (days != 7 && days != 30 && days != 90) throw new ApiException(400, "用量趋势天数只能为 7、30 或 90");
    }

    /**
     * 一次 SELECT 固定所有日桶；摘要由同批聚合生成，不发起第二次统计读取。
     */
    static Map<String, Object> query(Connection connection, String projectId, int days, String sessionId, String taskId, Instant asOf) throws SQLException {
        requireDays(days);
        LocalDate end = asOf.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1), start = end.minusDays(days);
        Map<LocalDate, Aggregate> buckets = new LinkedHashMap<>();
        for (int day = 0; day < days; day++) buckets.put(start.plusDays(day), new Aggregate());
        Aggregate summary = new Aggregate();
        try (var query = connection.prepareStatement(sql(!sessionId.isEmpty(), !taskId.isEmpty()))) {
            int parameter = 1;
            query.setString(parameter++, projectId);
            // 原记录为 UTC；各扩一天还可正确接纳带时区偏移的旧 ISO 时间。
            query.setString(parameter++, start.minusDays(1).toString()); query.setString(parameter++, end.plusDays(1).toString());
            if (!sessionId.isEmpty()) query.setString(parameter++, sessionId);
            if (!taskId.isEmpty()) query.setString(parameter++, taskId);
            query.setString(parameter++, start.toString()); query.setString(parameter, end.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    Unit unit = new Unit(); unit.records = rows.getLong("records");
                    for (int state = 0; state < STATES.size(); state++) unit.states[state] = rows.getLong("state" + state);
                    for (int field = 0; field < FIELDS.size(); field++) {
                        unit.sums[field] = new BigInteger(rows.getString("sum" + field)); unit.known[field] = rows.getLong("known" + field);
                    }
                    String name = rows.getString("unit");
                    buckets.get(LocalDate.parse(rows.getString("day"))).add(name, unit); summary.add(name, unit);
                }
            }
        }
        List<Map<String, Object>> visible = new ArrayList<>();
        for (var bucket : buckets.entrySet()) {
            Map<String, Object> value = obj("date", bucket.getKey().toString()); value.putAll(bucket.getValue().visible()); visible.add(value);
        }
        return obj("schemaVersion", 1, "source", "MODEL_REQUEST", "scope", obj("projectId", projectId, "sessionId", sessionId, "taskId", taskId),
            "days", days, "timeZone", "UTC", "bucket", "DAY", "from", start.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
            "toExclusive", end.atStartOfDay(ZoneOffset.UTC).toInstant().toString(), "asOf", asOf.toString(), "cost", "UNKNOWN", "cliInternalRequests", "UNKNOWN",
            "summary", summary.visible(), "buckets", visible);
    }

    private static String sql(boolean session, boolean task) {
        StringBuilder sql = new StringBuilder("WITH scoped AS MATERIALIZED (SELECT usage_trends_utc_day(json_extract(body,'$.createdAt')) AS day, "
            + "CASE WHEN json_extract(body,'$.unit') IN ('API_REQUEST','CLI_SESSION') THEN json_extract(body,'$.unit') ELSE 'UNKNOWN' END AS unit, "
            + "CASE WHEN json_extract(body,'$.state') IN ('DISPATCHING','SUCCEEDED','FAILED','CANCELLED') THEN json_extract(body,'$.state') ELSE 'UNKNOWN' END AS state");
        for (int field = 0; field < FIELDS.size(); field++) {
            String path = "'$.usage." + FIELDS.get(field) + "'", value = "json_extract(body," + path + ")";
            sql.append(", CASE WHEN json_type(body,").append(path).append(")='integer' AND typeof(").append(value)
                .append(")='integer' AND ").append(value).append(">=0 THEN ").append(value).append(" END AS token").append(field);
        }
        sql.append(" FROM entities WHERE kind='modelRequest' AND json_extract(body,'$.projectId')=? AND json_extract(body,'$.createdAt')>=? AND json_extract(body,'$.createdAt')<?");
        if (session) sql.append(" AND json_extract(body,'$.sessionId')=?");
        if (task) sql.append(" AND json_extract(body,'$.taskId')=?");
        sql.append(") SELECT day,unit,COUNT(*) AS records");
        for (int state = 0; state < STATES.size(); state++) sql.append(", SUM(CASE WHEN state='").append(STATES.get(state)).append("' THEN 1 ELSE 0 END) AS state").append(state);
        for (int field = 0; field < FIELDS.size(); field++) sql.append(", usage_trends_sum(token").append(field).append(") AS sum").append(field)
            .append(", COUNT(token").append(field).append(") AS known").append(field);
        return sql.append(" FROM scoped WHERE day>=? AND day<? GROUP BY day,unit ORDER BY day,unit").toString();
    }

    private static final class Aggregate {
        private long records;
        private final Map<String, Unit> units = new LinkedHashMap<>();

        private Aggregate() { for (String name : UNITS) units.put(name, new Unit()); }

        private void add(String name, Unit unit) {
            records = Math.addExact(records, unit.records); units.get(name).add(unit);
        }

        private Map<String, Object> visible() {
            Map<String, Object> values = new LinkedHashMap<>(); units.forEach((name, unit) -> values.put(name, unit.visible()));
            return obj("recordCount", records, "hasRecords", records > 0, "units", values);
        }
    }

    private static final class Unit {
        private long records;
        private final long[] states = new long[STATES.size()], known = new long[FIELDS.size()];
        private final BigInteger[] sums = new BigInteger[FIELDS.size()];

        private Unit() { java.util.Arrays.fill(sums, BigInteger.ZERO); }

        private void add(Unit other) {
            records = Math.addExact(records, other.records);
            for (int state = 0; state < states.length; state++) states[state] = Math.addExact(states[state], other.states[state]);
            for (int field = 0; field < sums.length; field++) { sums[field] = sums[field].add(other.sums[field]); known[field] = Math.addExact(known[field], other.known[field]); }
        }

        private Map<String, Object> visible() {
            Map<String, Object> counts = new LinkedHashMap<>(), tokens = new LinkedHashMap<>();
            for (int state = 0; state < states.length; state++) counts.put(STATES.get(state), states[state]);
            for (int field = 0; field < sums.length; field++) {
                boolean overflow = sums[field].compareTo(MAX_JSON_INTEGER) > 0;
                Object observed = overflow ? "UNKNOWN" : sums[field].longValueExact();
                tokens.put(FIELDS.get(field), obj("observedSum", observed, "knownCount", known[field], "unknownCount", records - known[field],
                    "total", records > 0 && known[field] == records && !overflow ? observed : "UNKNOWN", "overflow", overflow));
            }
            return obj("recordCount", records, "hasRecords", records > 0, "states", counts, "tokens", tokens);
        }
    }

    private static final class ExactSum extends Function.Aggregate {
        private BigInteger sum = BigInteger.ZERO;

        @Override
        protected void xStep() throws SQLException {
            String value = value_text(0);
            if (value != null) sum = sum.add(new BigInteger(value));
        }

        @Override
        protected void xFinal() throws SQLException { result(sum.toString()); sum = BigInteger.ZERO; }
    }
}

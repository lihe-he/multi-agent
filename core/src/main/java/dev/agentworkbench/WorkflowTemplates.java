package dev.agentworkbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;

/**
 * 三种协作方式提供可编辑的流程数据，根验证和独立审查由任务另行保留。
 */
final class WorkflowTemplates {
    static final List<String> MODES = List.of("LEAD", "PIPELINE", "ROUNDTABLE");
    private WorkflowTemplates() { }

    static String id(String mode) { return "workflow-" + mode.toLowerCase(java.util.Locale.ROOT); }
    static String name(String mode) {
        return switch (mode) { case "ROUNDTABLE" -> "圆桌提案、质疑与实现"; case "PIPELINE" -> "方案与实现流水线"; default -> "负责人调查与实现"; };
    }

    static Map<String, Object> dsl(String mode, Map<String, Map<String, Object>> actors) {
        Map<String, Object> research = actors.get("agent-research"), developer = actors.get("agent-developer"), reviewer = actors.get("agent-reviewer");
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<String> before;
        if ("ROUNDTABLE".equals(mode)) {
            List<String> proposals = new ArrayList<>(), critiques = new ArrayList<>();
            for (Map<String, Object> actor : List.of(research, developer, reviewer)) {
                String role = str(actor, "role"), proposal = "proposal-" + str(actor, "id").substring("agent-".length());
                proposals.add(proposal);
                nodes.add(node(proposal, "PROPOSAL", "独立提案 · " + role, actor, List.of(),
                    "独立调查根目标并提出方案，列出依据、异议、未知和可推翻条件。此轮不使用其他角色的提案。", false));
            }
            for (Map<String, Object> actor : List.of(research, developer, reviewer)) {
                String role = str(actor, "role"), critique = "critique-" + str(actor, "id").substring("agent-".length());
                critiques.add(critique);
                nodes.add(node(critique, "CRITIQUE", "交叉质疑 · " + role, actor, proposals,
                    "根据三份独立提案追溯原始证据，指出冲突、重复来源和需要验证的假设。", false));
            }
            nodes.add(node("synthesis", "SYNTHESIS", "汇总取舍与分歧", research, critiques,
                "汇总推荐方案、保留异议和未知，为实现提供可验证的交接；共识不替代验证。", false));
            before = List.of("synthesis");
        } else {
            String kind = "PIPELINE".equals(mode) ? "DESIGN" : "INVESTIGATE";
            nodes.add(node("analysis", kind, "PIPELINE".equals(mode) ? "方案与交接" : "负责人调查", research, List.of(),
                "调查根目标和固定现场，明确实现边界、证据、风险以及必要验证；按需使用已确认的副 Agent。", true));
            before = List.of("analysis");
        }
        nodes.add(node("implement", "IMPLEMENT", "完成需求实现", developer, before,
            "依据前置交接和根完成约定实际完成实现。保留原注释、已有工作和原失败；交付供根验证与独立审查。", true));
        return obj("dslVersion", WorkflowDsl.VERSION, "inputs", List.of("goal", "contract", "context", "baseline"),
            "maxParallel", 3, "nodes", nodes);
    }

    private static Map<String, Object> node(String id, String kind, String title, Map<String, Object> actor,
            List<String> dependencies, String goal, boolean delegate) {
        List<String> permissions = new ArrayList<>(List.of("read", "search"));
        if ("IMPLEMENT".equals(kind)) permissions.addAll(List.of("write", "verify", "mcp"));
        if (delegate) permissions.add("delegate");
        permissions.retainAll(strings(actor.get("capabilities")));
        return obj("id", id, "kind", kind, "title", title, "role", actor.get("role"), "goal", goal,
            "definitionId", actor.get("id"), "definitionVersion", actor.get("version"), "dependencies", dependencies,
            "inputs", dependencies.isEmpty() ? List.of("goal", "contract", "context", "baseline") : dependencies.stream().map(dependency -> dependency + ":result").toList(),
            "outputs", List.of(id + ":result"), "scope", actor.get("scope"), "permissions", permissions,
            "maxCalls", actor.get("maxCalls"), "timeoutSeconds", 900,
            "acceptance", List.of("保留原始依据、实际运行和版本；未确认项明确交接"), "termination", "完成、根取消、已确认限制或节点时限");
    }
}

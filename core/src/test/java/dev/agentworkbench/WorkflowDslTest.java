package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowDslTest {
    static Map<String, Object> node(String id, List<String> dependencies) {
        return obj("id", id, "kind", "INVESTIGATE", "title", "Node " + id, "role", "researcher", "goal", "Inspect " + id,
            "definitionId", "agent", "definitionVersion", 1, "dependencies", dependencies,
            "inputs", dependencies.isEmpty() ? List.of("goal") : dependencies.stream().map(dependency -> dependency + ":result").toList(),
            "outputs", List.of(id + ":result"), "scope", List.of("src/**"), "permissions", List.of("read", "search"),
            "maxCalls", 2, "acceptance", List.of("Original evidence remains attributable"), "termination", "Completion, root cancellation or deadline");
    }

    static Map<String, Object> graph(List<Map<String, Object>> nodes) {
        return obj("dslVersion", 1, "inputs", List.of("goal", "contract", "context", "baseline"), "maxParallel", 2, "nodes", new ArrayList<>(nodes));
    }

    @Test
    void forwardDeclaredDiamondHasTwoIndependentRootsAndOneJoinedInputSet() {
        Map<String, Object> normalized = WorkflowDsl.normalize(graph(List.of(node("join", List.of("left", "right")), node("left", List.of()), node("right", List.of()))));
        List<Map<String, Object>> nodes = objects(normalized.get("nodes"));
        assertEquals(List.of("left", "right", "join"), WorkflowDsl.order(nodes));
        assertEquals(java.util.Set.of("left", "right"), WorkflowDsl.ancestors("join", WorkflowDsl.byId(nodes)));
        assertEquals(List.of("left:result", "right:result"), map(WorkflowDsl.byId(nodes).get("join")).get("inputs"));
    }

    @Test
    void cycleAndMissingInputProviderCannotBecomeRunnablePlans() {
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(node("left", List.of("right")), node("right", List.of("left")))))).status());
        Map<String, Object> consumer = node("join", List.of("left")); consumer.put("inputs", List.of("absent:result"));
        assertTrue(assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(node("left", List.of()), consumer)))).getMessage().contains("输入提供方"));
        consumer.put("dependencies", List.of("absent"));
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(node("left", List.of()), consumer)))).status());
    }

    @Test
    void rootGatesAndAmbiguousArtifactsCannotBeReplacedByGeneratedNodes() {
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(node("verify", List.of()))))).status());
        Map<String, Object> left = node("left", List.of()), right = node("right", List.of());
        right.put("outputs", left.get("outputs"));
        assertTrue(assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(left, right)))).getMessage().contains("提供方不唯一"));
        right.put("outputs", List.of("goal"));
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(right)))).status());
    }

    @Test
    void proposalRemainsIndependentAndOtherReadOnlyKindsCannotAcquireWriteEffects() {
        Map<String, Object> proposal = node("proposal", List.of()); proposal.put("kind", "PROPOSAL");
        proposal.put("permissions", List.of("read", "delegate"));
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(proposal)))).status());
        proposal.put("permissions", List.of("read", "search"));
        assertDoesNotThrow(() -> WorkflowDsl.normalize(graph(List.of(proposal))));
        proposal.put("kind", "CRITIQUE"); proposal.put("permissions", List.of("read", "write"));
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(proposal)))).status());
    }

    @Test
    void normalizedSnapshotAndDigestDoNotFollowLaterInputMutationOrObjectKeyOrdering() {
        Map<String, Object> sourceNode = node("source", List.of()); sourceNode.put("scope", List.of("src\\**"));
        Map<String, Object> normalized = WorkflowDsl.normalize(graph(List.of(sourceNode)));
        ObjectMapper mapper = new ObjectMapper(); String digest = WorkflowDsl.digest(normalized, mapper);
        sourceNode.put("goal", "Changed after the snapshot");
        assertEquals("Inspect source", str(objects(normalized.get("nodes")).getFirst(), "goal"));
        assertEquals(List.of("src/**"), objects(normalized.get("nodes")).getFirst().get("scope"));
        List<String> keys = new ArrayList<>(normalized.keySet()); java.util.Collections.reverse(keys);
        Map<String, Object> reordered = new LinkedHashMap<>(); keys.forEach(key -> reordered.put(key, normalized.get(key)));
        assertEquals(digest, WorkflowDsl.digest(reordered, mapper));
        assertNotEquals(digest, WorkflowDsl.digest(WorkflowDsl.normalize(graph(List.of(sourceNode))), mapper));
    }

    @ParameterizedTest
    @MethodSource("invalidNodeFields")
    void malformedTypesAndHostInstructionsAreRejectedBeforeAnyDispatch(Map<String, Object> mutation) {
        Map<String, Object> source = node("source", List.of()); source.putAll(mutation);
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(List.of(source)))).status());
    }

    static Stream<Arguments> invalidNodeFields() {
        return Stream.of(obj("definitionVersion", "1"), obj("definitionVersion", 1.5), obj("maxCalls", 0), obj("timeoutSeconds", 901),
            obj("scope", List.of("../other/**")), obj("scope", List.of("C:/other/**")), obj("permissions", List.of("read", "publish")),
            obj("permissions", List.of("read", "read")), obj("role", true), obj("kind", "SCRIPT"), obj("scope", null),
            obj("workspace", "D:/another-project"), obj("script", "arbitrary host instructions"), obj("dependencies", List.of(42)))
            .map(Arguments::of);
    }

    @Test
    void malformedRootAndOversizedRolesCannotHideInsideAnOtherwiseValidGraph() {
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(null)).status());
        Map<String, Object> invalid = graph(List.of(node("source", List.of()))); invalid.put("nodes", List.of("not a node"));
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(invalid)).status());
        invalid.put("nodes", List.of(node("source", List.of()))); invalid.put("maxParallel", 7);
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(invalid)).status());
        List<Map<String, Object>> roles = new ArrayList<>();
        for (int number = 0; number < 7; number++) { Map<String, Object> node = node("node" + number, List.of()); node.put("role", "role" + number); roles.add(node); }
        assertEquals(400, assertThrows(ApiException.class, () -> WorkflowDsl.normalize(graph(roles))).status());
    }
}

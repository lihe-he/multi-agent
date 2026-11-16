package dev.agentworkbench.runtime;

import dev.agentworkbench.ApiException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GitBlobBatchTest {
    private final byte[] binary = new byte[] { 0, -1, 13, 10, 32, 0, 10 };

    @ParameterizedTest
    @ValueSource(strings = {"SHA-1", "SHA-256"})
    void originalBinaryEmptyAndDelimiterBytesRoundTripInBothGitObjectFormats(String algorithm) throws Exception {
        String first = hash(algorithm, binary), empty = hash(algorithm, new byte[0]);
        List<String> requested = List.of(first, empty);
        Map<String, Long> sizes = GitBlobBatch.sizes(requested, ascii(first + " blob " + binary.length + "\n" + empty + " blob 0\n"));
        Map<String, byte[]> contents = GitBlobBatch.contents(sizes, response(first, binary, empty, new byte[0]));
        assertEquals(first + "\n" + empty + "\n", GitBlobBatch.request(requested));
        assertArrayEquals(binary, contents.get(first)); assertArrayEquals(new byte[0], contents.get(empty));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "foreign", "type", "negative", "overflow", "zero-prefix", "spaces", "crlf", "truncated", "extra", "long-header", "non-ascii"})
    void invalidInformationCannotAuthorizeContentReads(String failure) throws Exception {
        String object = hash("SHA-1", binary), header = object + " blob " + binary.length + "\n";
        byte[] output = ascii(switch (failure) {
            case "missing" -> object + " missing\n";
            case "foreign" -> "a".repeat(40) + " blob 7\n";
            case "type" -> object + " tree 7\n";
            case "negative" -> object + " blob -1\n";
            case "overflow" -> object + " blob 9223372036854775808\n";
            case "zero-prefix" -> object + " blob 07\n";
            case "spaces" -> object + "  blob 7\n";
            case "crlf" -> object + " blob 7\r\n";
            case "truncated" -> object + " blob 7";
            case "extra" -> header + "unrequested\n";
            case "long-header" -> object + " blob " + "7".repeat(96) + "\n";
            default -> header;
        });
        if (failure.equals("non-ascii")) output[0] = -1;
        assertEquals(409, assertThrows(ApiException.class, () -> GitBlobBatch.sizes(List.of(object), output)).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "length", "separator", "missing-content", "extra-content", "order", "missing-object"})
    void incompleteOrChangedContentIsRejectedEvenWithValidInformation(String failure) throws Exception {
        String object = hash("SHA-1", binary), empty = hash("SHA-1", new byte[0]);
        Map<String, Long> requested = new LinkedHashMap<>(); requested.put(object, (long) binary.length); requested.put(empty, 0L);
        byte[] output = response(object, binary, empty, new byte[0]);
        int firstContent = (object + " blob " + binary.length + "\n").length();
        if (failure.equals("hash")) output[firstContent] = 42;
        if (failure.equals("length")) output[(object + " blob ").length()] = '8';
        if (failure.equals("separator")) output[firstContent + binary.length] = 0;
        if (failure.equals("missing-content")) output = Arrays.copyOf(output, firstContent + binary.length - 1);
        if (failure.equals("extra-content")) output = Arrays.copyOf(output, output.length + 1);
        if (failure.equals("order")) output = response(empty, new byte[0], object, binary);
        if (failure.equals("missing-object")) output = ascii(object + " missing\n");
        byte[] original = output;
        assertEquals(409, assertThrows(ApiException.class, () -> GitBlobBatch.contents(requested, original)).status());
    }

    @Test
    void requestsAndOutputAreBoundedBeforeStartingGit() {
        List<String> tooMany = IntStream.range(1, 258).mapToObj(number -> String.format("%040x", number)).toList();
        assertThrows(ApiException.class, () -> GitBlobBatch.request(tooMany));
        assertThrows(ApiException.class, () -> GitBlobBatch.request(List.of("a".repeat(40), "a".repeat(40))));
        assertThrows(ApiException.class, () -> GitBlobBatch.request(List.of("HEAD:app.txt")));
        assertThrows(ApiException.class, () -> GitBlobBatch.contentBound(Map.of("a".repeat(40), 256_001L)));
        assertThrows(ApiException.class, () -> GitBlobBatch.contentBound(Map.of("a".repeat(40), -1L)));
        Map<String, Long> oversized = new LinkedHashMap<>(); tooMany.subList(0, 9).forEach(object -> oversized.put(object, 256_000L));
        assertThrows(ApiException.class, () -> GitBlobBatch.contentBound(oversized));
    }

    private byte[] response(String first, byte[] bytes, String second, byte[] other) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ascii(first + " blob " + bytes.length + "\n")); output.write(bytes); output.write('\n');
        output.write(ascii(second + " blob " + other.length + "\n")); output.write(other); output.write('\n'); return output.toByteArray();
    }

    private String hash(String algorithm, byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm); digest.update(ascii("blob " + bytes.length + "\0")); digest.update(bytes); return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}

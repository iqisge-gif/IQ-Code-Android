import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContextCompactionBoundaryStructureTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String source = Files.readString(root.resolve(
            "src/com/termux/app/iqcode/core/ContextCompactor.java"), StandardCharsets.UTF_8);

        require(source.contains("if (!isUserRequest(message)) continue;"),
            "compaction boundaries must start at user requests");
        require(source.contains("\"text\".equals(type) || \"image\".equals(type)"),
            "user requests must be distinguished from tool-result messages");
        require(!source.contains("if (message == null || !\"assistant\".equals(message.optString(\"role\", \"\"))) continue;"),
            "assistant responses must not be treated as API-round starts");
        require(source.contains("int[] cumulativeTokens = cumulativeRoughTokens(source)")
                && source.contains("cumulativeTokens[end] - cumulativeTokens[start]"),
            "recent-round selection must tokenize messages once instead of rescanning every round");
        require(source.contains("JSONArray out = reduceForSummary")
                && !source.contains("JSONArray out = cloneArray(reduced)"),
            "summary request construction must not stringify and reparse the reduced history");

        System.out.println("ContextCompactionBoundaryStructureTest PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

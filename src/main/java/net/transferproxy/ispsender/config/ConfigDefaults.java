package net.transferproxy.ispsender.config;

import net.transferproxy.api.util.ResourceUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appends top-level keys that exist in the bundled config.yml but not in the server's copy. The
 * text of the admin's file is never rewritten: only missing blocks (with the comment lines directly
 * above them) are added at the end, so existing values and comments stay as they are.
 */
public final class ConfigDefaults {
    /**
     * Pools whose bundled values are placeholder hosts. They are admin-defined routing, so a
     * missing key must stay missing instead of sending players to example.com.
     */
    private static final Set<String> NOT_FILLED =
            Set.of("asn-servers", "country-servers", "default-server");

    private static final Pattern TOP_KEY =
            Pattern.compile("^(?:\"([^\"]+)\"|'([^']+)'|([^\\s#:\\-][^:]*?))\\s*:(?:\\s.*)?$");

    private ConfigDefaults() {}

    /**
     * Adds the missing keys to {@code file}. A missing or unparsable file is left alone.
     *
     * @return the keys that were added, in file order
     */
    public static List<String> fill(Path file, InputStream bundled) throws IOException {
        if (bundled == null || !Files.isRegularFile(file)) return List.of();
        String existing = Files.readString(file, StandardCharsets.UTF_8);
        String defaults = new String(bundled.readAllBytes(), StandardCharsets.UTF_8);
        Set<String> present;
        try {
            present = topLevelKeys(existing);
            if (present == null) present = Set.of();
            if (topLevelKeys(defaults) == null) return List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
        String eol = existing.contains("\r\n") ? "\r\n" : "\n";
        List<String> added = new ArrayList<>();
        StringBuilder out = new StringBuilder(existing);
        for (Map.Entry<String, List<String>> block : blocks(defaults).entrySet()) {
            String key = block.getKey();
            if (present.contains(key) || NOT_FILLED.contains(key)) continue;
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append(eol);
            if (out.length() > 0) out.append(eol);
            for (String line : block.getValue()) out.append(line).append(eol);
            added.add(key);
        }
        if (added.isEmpty()) return List.of();
        // Everything must still parse; otherwise keep the admin's file untouched.
        try {
            topLevelKeys(out.toString());
        } catch (RuntimeException e) {
            return List.of();
        }
        write(file, out.toString());
        return added;
    }

    /** Null for an empty document; a parse error or a non-map root throws. */
    private static Set<String> topLevelKeys(String yaml) {
        Object root = ResourceUtil.getDefaultYamlMapper().readValue(yaml, Object.class);
        if (root == null) return null;
        if (!(root instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Not a YAML mapping");
        Set<String> keys = new LinkedHashSet<>();
        for (Object key : map.keySet()) keys.add(String.valueOf(key));
        return keys;
    }

    /** Raw text of every top-level key in the bundled file, with the comments directly above it. */
    private static Map<String, List<String>> blocks(String defaults) {
        String[] lines = defaults.split("\\r?\\n", -1);
        List<Integer> keyLines = new ArrayList<>();
        List<String> keyNames = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TOP_KEY.matcher(lines[i]);
            if (!m.matches()) continue;
            keyLines.add(i);
            keyNames.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
        }
        // A comment line directly above a key (no blank line between) belongs to that key.
        int[] starts = new int[keyLines.size()];
        for (int k = 0; k < starts.length; k++) {
            int start = keyLines.get(k);
            int floor = k == 0 ? 0 : keyLines.get(k - 1) + 1;
            while (start > floor && lines[start - 1].startsWith("#")) start--;
            starts[k] = start;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int k = 0; k < starts.length; k++) {
            int end = k + 1 < starts.length ? starts[k + 1] : lines.length;
            while (end > starts[k] && lines[end - 1].isBlank()) end--;
            result.putIfAbsent(keyNames.get(k), List.of(lines).subList(starts[k], end));
        }
        return result;
    }

    private static void write(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(dir, "config-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

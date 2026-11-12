package incari.tools.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Whole-file changes to the vendor tree: classes Incari deletes outright, and classes it
 * replaces with its own source file.
 *
 * <p>These don't go through patches on purpose. A diff that deletes or rewrites a whole
 * Mojang file has to contain every one of its lines, which would republish them. A manifest
 * line names the class and contains no Mojang code at all.
 *
 * <p>Format of {@code patches/classes.txt}, one entry per line, {@code #} starts a comment:
 * <pre>
 * delete  net.minecraft.client.telemetry.ClientTelemetryManager
 * replace com.mojang.blaze3d.vertex.ByteBufferBuilder  render-webgl2/src/vendor/java/com/mojang/blaze3d/vertex/ByteBufferBuilder.java  26.3@3f2a9c1b07de
 * </pre>
 * Replacement paths are relative to the repo root. A replace line's last column, its {@link Stamp},
 * records which Mojang original the replacement was last checked against, so a new Minecraft
 * version (or decompiler) that changes the original is flagged for review instead of drifting
 * silently. {@code ./incari classes --stamp} writes it; nobody types it.
 */
public record ClassManifest(List<Entry> entries) {

    public static final String FILE_NAME = "classes.txt";

    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    private static final Pattern STAMP = Pattern.compile("([^@\\s]+)@([0-9a-f]{7,40})");

    /** A replace line up to its source path, its stamp if any, and what follows (a comment). */
    private static final Pattern REPLACE_LINE =
            Pattern.compile("^(\\s*replace\\s+(\\S+)\\s+[^\\s#]+)(?:\\s+[^\\s#]+)?(\\s*(?:#.*)?)$");

    /** Blob ids are abbreviated to this many hex digits in the manifest. */
    public static final int STAMP_DIGITS = 12;

    public sealed interface Entry {
        String className();

        /** Path of the class's source file, relative to the client tree root. */
        default String treePath() {
            return Workspace.CLIENT_SOURCE_DIR + "/" + className().replace('.', '/') + ".java";
        }
    }

    public record Delete(String className) implements Entry {}

    /**
     * The vendor original a replacement was checked against: the Minecraft version and the git blob
     * id of its decompiled, unpatched source (abbreviated). Names no Mojang code.
     */
    public record Stamp(String version, String blob) {
        @Override
        public String toString() {
            return version + "@" + blob;
        }

        /** Whether a full or abbreviated blob id is this stamp's file. */
        public boolean matches(String otherBlob) {
            return otherBlob.startsWith(blob) || blob.startsWith(otherBlob);
        }
    }

    /** {@code stamp} is null until {@code ./incari classes --stamp} records one. */
    public record Replace(
            String className, String source, @Nullable Stamp stamp) implements Entry {
        public Replace(String className, String source) {
            this(className, source, null);
        }
    }

    public static ClassManifest load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ClassManifest(List.of());
        }
        return parse(Files.readAllLines(file), file.toString());
    }

    static ClassManifest parse(List<String> lines, String origin) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int hash = line.indexOf('#');
            String content = (hash >= 0 ? line.substring(0, hash) : line).strip();
            if (content.isEmpty()) {
                continue;
            }
            String where = origin + ":" + (i + 1);
            String[] parts = content.split("\\s+", -1);
            Entry entry =
                    switch (parts[0]) {
                        case "delete" -> {
                            expect(parts.length == 2, where, "expected: delete <class>");
                            yield new Delete(className(parts[1], where));
                        }
                        case "replace" -> {
                            expect(
                                    parts.length == 3 || parts.length == 4,
                                    where,
                                    "expected: replace <class> <source path> [<version>@<vendor blob>]");
                            String source = parts[2];
                            expect(
                                    !source.startsWith("/") && !source.contains("..") && !source.startsWith("work/"),
                                    where,
                                    "replacement source must be a repo-relative path outside work/");
                            @Nullable Stamp stamp = null;
                            if (parts.length == 4) {
                                Matcher m = STAMP.matcher(parts[3]);
                                expect(
                                        m.matches(),
                                        where,
                                        "'" + parts[3] + "' is not a stamp (<version>@<vendor blob>);"
                                                + " write it with ./incari classes --stamp");
                                stamp = new Stamp(m.group(1), m.group(2));
                            }
                            yield new Replace(className(parts[1], where), source, stamp);
                        }
                        default -> throw new CliException(where + ": unknown action '" + parts[0] + "'");
                    };
            expect(seen.add(entry.className()), where, entry.className() + " is listed twice");
            entries.add(entry);
        }
        return new ClassManifest(List.copyOf(entries));
    }

    /**
     * The manifest's lines with each replace line's stamp set from {@code stamps} (by class name),
     * aligned in one column. Every other line, comments included, is kept as it is.
     */
    static List<String> withStamps(List<String> lines, Map<String, Stamp> stamps) {
        int width = 0;
        for (String line : lines) {
            Matcher m = REPLACE_LINE.matcher(line);
            if (m.matches() && stamps.containsKey(m.group(2))) {
                width = Math.max(width, m.group(1).length());
            }
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher m = REPLACE_LINE.matcher(line);
            Stamp stamp = m.matches() ? stamps.get(m.group(2)) : null;
            if (stamp == null) {
                out.add(line);
                continue;
            }
            String head = m.group(1);
            out.add(head + " ".repeat(width - head.length() + 2) + stamp + m.group(3));
        }
        return out;
    }

    /** Tree paths this manifest owns. Patches must not touch them. */
    public Set<String> treePaths() {
        Set<String> paths = new HashSet<>();
        for (Entry entry : entries) {
            paths.add(entry.treePath());
        }
        return paths;
    }

    private static String className(String value, String where) {
        expect(
                CLASS_NAME.matcher(value).matches() && !value.contains("$"),
                where,
                "'" + value + "' is not a top-level class name (nested classes can't be replaced alone)");
        return value;
    }

    private static void expect(boolean condition, String where, String message) {
        if (!condition) {
            throw new CliException(where + ": " + message);
        }
    }
}

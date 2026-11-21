import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * No public entry point reaches the 4.x QUIC stack.
 *
 *     java scripts/EntryPointIsolationCheck.java <repository root>
 *     java scripts/EntryPointIsolationCheck.java --selftest
 *
 * The 4.x stack is every public top-level type of :socket-quic-quiche's main source sets outside package
 * `com.ditchoom.socket.quic.core`, plus the public QuicEngine surface of :socket-quic (QuicEngine, QuicEngineSpi,
 * UnsupportedQuicEngine). The entry-point modules are :socket-quic-default, :socket-http3,
 * :socket-webtransport and :socket-testsuite; their main source sets may not mention any of those names, and may not
 * mention package `quic.core` at all. Everything in package `quic.core` must be `internal` or `private`.
 *
 * Today's violations are listed in scripts/entry-point-isolation.allowlist as `<file><TAB><symbol>`. The check fails on
 * a violation that is not listed and on a listed entry that no longer matches, so the list can only shrink.
 * Finding no source at all is a failure: a scan that matched nothing must not look like a clean one.
 */
public class EntryPointIsolationCheck {
    static final String QUICHE = "socket-quic-quiche";
    static final String CORE_DIR = "/com/ditchoom/socket/quic/core/";
    static final List<String> ENTRY_MODULES = List.of("socket-quic-default", "socket-http3", "socket-webtransport", "socket-testsuite");
    static final List<String> QUIC_ENGINE_SURFACE = List.of("QuicEngine", "QuicEngineSpi", "UnsupportedQuicEngine");
    static final String ALLOWLIST = "scripts/entry-point-isolation.allowlist";

    static final Pattern TYPE_DECL = Pattern.compile("^((?:[a-z]+\\s+)*)(?:class|object|interface|typealias)\\s+`?(\\w+)`?");
    static final Pattern CORE_PUBLIC = Pattern.compile(
        "^(?:public\\s+|open\\s+|abstract\\s+|sealed\\s+|data\\s+|enum\\s+|value\\s+|inline\\s+|const\\s+|expect\\s+|actual\\s+|annotation\\s+|suspend\\s+|tailrec\\s+|operator\\s+)*"
            + "(?:class|object|interface|typealias|fun|val|var)\\b");
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final Pattern CORE_REFERENCE = Pattern.compile("\\bquic\\s*\\.\\s*core\\b");

    record Violation(String file, String symbol, String detail) {
        String key() {
            return file + "\t" + symbol;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--selftest")) {
            selftest();
            return;
        }
        if (args.length != 1) {
            System.err.println("usage: EntryPointIsolationCheck <repository root> | --selftest");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        Result result = run(root, readAllowlist(root.resolve(ALLOWLIST)));
        System.out.println("EntryPointIsolationCheck: " + result.scanned + " entry-point files, " + result.fourX + " 4.x names, "
            + result.allowed + " allow-listed");
        for (String line : result.failures) System.err.println(line);
        if (!result.failures.isEmpty()) {
            System.err.println("EntryPointIsolationCheck: " + result.failures.size() + " failure(s)");
            System.exit(1);
        }
    }

    record Result(int scanned, int fourX, int allowed, List<String> failures) {}

    static Set<String> readAllowlist(Path file) throws IOException {
        Set<String> entries = new TreeSet<>();
        if (!Files.exists(file)) return entries;
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            entries.add(t);
        }
        return entries;
    }

    static Result run(Path root, Set<String> allowlist) throws IOException {
        List<String> failures = new ArrayList<>();
        Set<String> fourX = new TreeSet<>(QUIC_ENGINE_SURFACE);
        Set<String> declaredEngineSurface = new TreeSet<>();

        List<Path> quicheFiles = mainSources(root, QUICHE);
        if (quicheFiles.isEmpty()) failures.add("no source found under " + QUICHE + "; refusing to pass an empty scan");
        for (Path file : quicheFiles) {
            String rel = rel(root, file);
            boolean inCore = rel.contains(CORE_DIR);
            for (String line : stripped(file).split("\n", -1)) {
                if (inCore) {
                    if (CORE_PUBLIC.matcher(line).find()) failures.add(rel + ": public declaration in package quic.core: " + line.strip());
                    continue;
                }
                Matcher m = TYPE_DECL.matcher(line);
                if (m.find() && !m.group(1).contains("internal") && !m.group(1).contains("private")) fourX.add(m.group(2));
            }
        }
        for (Path file : mainSources(root, "socket-quic")) {
            for (String name : QUIC_ENGINE_SURFACE) {
                if (Pattern.compile("\\b(?:class|object|interface)\\s+" + name + "\\b").matcher(stripped(file)).find()) declaredEngineSurface.add(name);
            }
        }
        for (String name : QUIC_ENGINE_SURFACE) {
            if (!declaredEngineSurface.contains(name)) failures.add("QUIC engine surface name " + name + " is no longer declared in socket-quic; update the list");
        }

        Map<String, Violation> found = new TreeMap<>();
        int scanned = 0;
        for (String module : ENTRY_MODULES) {
            for (Path file : mainSources(root, module)) {
                scanned++;
                String rel = rel(root, file);
                String text = stripped(file);
                if (CORE_REFERENCE.matcher(text).find()) failures.add(rel + ": mentions package quic.core");
                Set<String> names = new TreeSet<>();
                Matcher id = IDENTIFIER.matcher(text);
                while (id.find()) if (fourX.contains(id.group())) names.add(id.group());
                for (String name : names) {
                    Violation v = new Violation(rel, name, "references 4.x type " + name);
                    found.put(v.key(), v);
                }
            }
        }
        if (scanned == 0) failures.add("no entry-point source found; refusing to pass an empty scan");

        for (Violation v : found.values()) {
            if (!allowlist.contains(v.key())) failures.add(v.file + ": " + v.detail + " (not in " + ALLOWLIST + ")");
        }
        for (String entry : allowlist) {
            if (!found.containsKey(entry)) failures.add(ALLOWLIST + ": stale entry, no longer a violation: " + entry.replace('\t', ' '));
        }
        return new Result(scanned, fourX.size(), allowlist.size(), failures);
    }

    /** `<module>/src/<set>Main/kotlin` for every source set whose name ends in Main. */
    static List<Path> mainSources(Path root, String module) throws IOException {
        Path src = root.resolve(module).resolve("src");
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(src)) return out;
        try (Stream<Path> sets = Files.list(src)) {
            for (Path set : (Iterable<Path>) sets.filter(p -> p.getFileName().toString().endsWith("Main"))::iterator) {
                Path kotlin = set.resolve("kotlin");
                if (!Files.isDirectory(kotlin)) continue;
                try (Stream<Path> walk = Files.walk(kotlin)) {
                    walk.filter(p -> p.toString().endsWith(".kt")).sorted().forEach(out::add);
                }
            }
        }
        return out;
    }

    static String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    static String stripped(Path file) throws IOException {
        return strip(Files.readString(file));
    }

    /** Blanks comments and string literals, keeping line structure. */
    static String strip(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (s.startsWith("/*", i)) {
                int depth = 0;
                while (i < n) {
                    if (s.startsWith("/*", i)) { depth++; i += 2; }
                    else if (s.startsWith("*/", i)) { depth--; i += 2; if (depth == 0) break; }
                    else { if (s.charAt(i) == '\n') out.append('\n'); i++; }
                }
            } else if (s.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !s.startsWith("\"\"\"", i)) { if (s.charAt(i) == '\n') out.append('\n'); i++; }
                i += 3;
            } else if (c == '"') {
                i++;
                while (i < n && s.charAt(i) != '"' && s.charAt(i) != '\n') i += s.charAt(i) == '\\' ? 2 : 1;
                i++;
            } else if (c == '\'' && i + 2 < n && (s.charAt(i + 2) == '\'' || s.charAt(i + 1) == '\\')) {
                i++;
                while (i < n && s.charAt(i) != '\'') i += s.charAt(i) == '\\' ? 2 : 1;
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ---- selftest ----

    static void selftest() throws IOException {
        Path root = Files.createTempDirectory("entry-point-isolation");
        write(root, QUICHE + "/src/commonMain/kotlin/com/ditchoom/socket/quic/Engine.kt",
            "package com.ditchoom.socket.quic\nobject LegacyEngine\ninternal class HiddenThing\n");
        write(root, QUICHE + "/src/commonMain/kotlin/com/ditchoom/socket/quic/core/Core.kt",
            "package com.ditchoom.socket.quic.core\ninternal class CoreThing\n");
        write(root, "socket-quic/src/commonMain/kotlin/com/ditchoom/socket/quic/Api.kt",
            "interface QuicEngine\nannotation class QuicEngineSpi\nclass UnsupportedQuicEngine\n");
        String clean = "package p\n// LegacyEngine in a comment\nval s = \"LegacyEngine\"\nclass Mine { val h = HiddenThing }\n";
        write(root, "socket-quic-default/src/commonMain/kotlin/Clean.kt", clean);
        expect(root, Set.of(), 0, "clean tree passes");

        write(root, "socket-quic-default/src/commonMain/kotlin/Uses.kt", "package p\nval e = LegacyEngine\n");
        expect(root, Set.of(), 1, "new violation fails");
        Set<String> listed = Set.of("socket-quic-default/src/commonMain/kotlin/Uses.kt\tLegacyEngine");
        expect(root, listed, 0, "listed violation passes");
        Files.delete(root.resolve("socket-quic-default/src/commonMain/kotlin/Uses.kt"));
        expect(root, listed, 1, "stale allow-list entry fails");

        write(root, "socket-http3/src/jvmMain/kotlin/Core.kt", "import com.ditchoom.socket.quic.core.CoreThing\n");
        expect(root, Set.of(), 1, "import of package core fails");
        Files.delete(root.resolve("socket-http3/src/jvmMain/kotlin/Core.kt"));

        write(root, "socket-webtransport/src/commonMain/kotlin/Spi.kt", "@QuicEngineSpi\nval x = 1\n");
        expect(root, Set.of(), 1, "engine surface name fails");
        Files.delete(root.resolve("socket-webtransport/src/commonMain/kotlin/Spi.kt"));

        write(root, QUICHE + "/src/jvmMain/kotlin/com/ditchoom/socket/quic/core/Open.kt", "package x\nclass Leaky\n");
        expect(root, Set.of(), 1, "public declaration in core fails");
        write(root, QUICHE + "/src/jvmMain/kotlin/com/ditchoom/socket/quic/core/Open.kt", "package x\nprivate fun ok() {}\n");
        expect(root, Set.of(), 0, "private declaration in core passes");

        write(root, "socket-quic-default/src/commonTest/kotlin/T.kt", "val e = LegacyEngine\n");
        expect(root, Set.of(), 0, "test source sets are not scanned");
        System.out.println("EntryPointIsolationCheck selftest: every rule catches its planted violation, clean tree passes");
    }

    static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    static void expect(Path root, Set<String> allowlist, int failures, String what) throws IOException {
        List<String> got = run(root, allowlist).failures;
        if (got.size() != failures) {
            System.err.println("selftest FAILED: " + what + ": expected " + failures + " failure(s), got " + got);
            System.exit(1);
        }
    }
}

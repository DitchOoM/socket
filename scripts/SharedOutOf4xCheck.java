import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * No core or shared file of :socket-quic-quiche uses a declaration that only a 4.x file declares.
 *
 *     java scripts/SharedOutOf4xCheck.java <repository root>
 *     java scripts/SharedOutOf4xCheck.java --selftest
 *
 * scripts/x5-4x-files.list names the 4.x files, one repository-relative path per line: the files that go when the
 * 4.x stack is deleted. A name is a 4.x name when a listed file declares it at top level (not private) and no unlisted
 * file does. Every main source set of :socket-quic-quiche is scanned; a file that is not listed (a core file, or any
 * file the 4.x stack will leave behind) may not mention a 4.x name, comments and string literals aside. The same name
 * declared on both sides (an expect in a shared file, its actual in a listed one) is not a 4.x name.
 *
 * Files in package `quic.core` may never be excused. Other violations still open are listed in
 * scripts/x5-4x-shared-couplings.allowlist as `<file><TAB><name>`. Both files can only shrink: the check fails on a
 * listed file that no longer exists, on a list entry that no longer matches a violation, and on any unlisted violation.
 * Finding no source is a failure: a scan that matched nothing must not look like a clean one.
 */
public class SharedOutOf4xCheck {
    static final String QUICHE = "socket-quic-quiche";
    static final String CORE_DIR = "/com/ditchoom/socket/quic/core/";
    static final String LIST = "scripts/x5-4x-files.list";
    static final String ALLOWLIST = "scripts/x5-4x-shared-couplings.allowlist";

    static final Pattern DECL = Pattern.compile(
        "^(?!private\\b)(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*"
            + "(?:(?:public|internal|expect|actual|data|sealed|enum|value|inline|fun|abstract|open|const|suspend|tailrec|operator"
            + "|annotation|infix|external|lateinit)\\s+)*"
            + "(?:class|interface|object|typealias|fun|val|var)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?, *]+\\.)?`?(\\w+)`?",
        Pattern.MULTILINE);
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    record Result(int files, int fourXFiles, int fourXNames, List<String> failures) {}

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--selftest")) {
            selftest();
            return;
        }
        if (args.length != 1) {
            System.err.println("usage: SharedOutOf4xCheck <repository root> | --selftest");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        Result result = run(root, readLines(root.resolve(LIST)), readLines(root.resolve(ALLOWLIST)));
        System.out.println("SharedOutOf4xCheck: " + result.files + " files, " + result.fourXFiles + " listed, " + result.fourXNames
            + " 4.x names");
        for (String line : result.failures) System.err.println(line);
        if (!result.failures.isEmpty()) {
            System.err.println("SharedOutOf4xCheck: " + result.failures.size() + " failure(s)");
            System.exit(1);
        }
    }

    static Set<String> readLines(Path file) throws IOException {
        Set<String> entries = new TreeSet<>();
        if (!Files.exists(file)) return entries;
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            entries.add(t);
        }
        return entries;
    }

    static Result run(Path root, Set<String> listed, Set<String> allowlist) throws IOException {
        List<String> failures = new ArrayList<>();
        List<Path> sources = mainSources(root);
        if (sources.isEmpty()) failures.add("no source found under " + QUICHE + "; refusing to pass an empty scan");

        Map<String, String> text = new TreeMap<>();
        for (Path file : sources) text.put(rel(root, file), strip(Files.readString(file)));
        for (String entry : listed) {
            if (!text.containsKey(entry)) failures.add(LIST + ": stale entry, no such file: " + entry);
        }

        Set<String> declaredInListed = new TreeSet<>();
        Set<String> declaredElsewhere = new HashSet<>();
        for (Map.Entry<String, String> e : text.entrySet()) {
            Matcher m = DECL.matcher(e.getValue());
            while (m.find()) (listed.contains(e.getKey()) ? declaredInListed : declaredElsewhere).add(m.group(1));
        }
        Set<String> fourX = new TreeSet<>(declaredInListed);
        fourX.removeAll(declaredElsewhere);

        Map<String, Boolean> found = new TreeMap<>();
        for (Map.Entry<String, String> e : text.entrySet()) {
            String file = e.getKey();
            if (listed.contains(file)) continue;
            Set<String> names = new TreeSet<>();
            Matcher id = IDENTIFIER.matcher(e.getValue());
            while (id.find()) if (fourX.contains(id.group())) names.add(id.group());
            for (String name : names) found.put(file + "\t" + name, file.contains(CORE_DIR));
        }
        for (Map.Entry<String, Boolean> v : found.entrySet()) {
            String[] parts = v.getKey().split("\t");
            if (v.getValue()) {
                failures.add(parts[0] + ": core production source uses 4.x declaration " + parts[1]);
            } else if (!allowlist.contains(v.getKey())) {
                failures.add(parts[0] + ": shared source uses 4.x declaration " + parts[1] + " (not in " + ALLOWLIST + ")");
            }
        }
        for (String entry : allowlist) {
            if (!found.containsKey(entry)) failures.add(ALLOWLIST + ": stale entry, no longer a violation: " + entry.replace('\t', ' '));
            else if (found.get(entry)) failures.add(ALLOWLIST + ": core files cannot be excused: " + entry.replace('\t', ' '));
        }
        return new Result(text.size(), listed.size(), fourX.size(), failures);
    }

    /** Every `.kt` file under `socket-quic-quiche/src/<set>Main/kotlin`. */
    static List<Path> mainSources(Path root) throws IOException {
        Path src = root.resolve(QUICHE).resolve("src");
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

    static final String PKG = QUICHE + "/src/commonMain/kotlin/com/ditchoom/socket/quic/";
    static final String DRIVER = PKG + "Driver.kt";
    static final String SHARED = PKG + "Shared.kt";
    static final String CORE = PKG + "core/Core.kt";

    static void selftest() throws IOException {
        Path root = Files.createTempDirectory("shared-out-of-4x");
        write(root, DRIVER, "package p\nclass FourXDriver\ninternal fun fourXHelper() {}\nprivate val hidden = 1\n");
        write(root, SHARED, "package p\nclass Shared { val h = hidden }\n");
        write(root, CORE, "package p.core\ninternal class CoreThing\n");
        Set<String> listed = Set.of(DRIVER);
        expect(root, listed, Set.of(), 0, "clean tree passes (a private name is not a 4.x name)");

        write(root, SHARED, "package p\n// FourXDriver in a comment\nval s = \"FourXDriver\"\nclass Mine\n");
        expect(root, listed, Set.of(), 0, "comments and string literals are not uses");

        write(root, CORE, "package p.core\ninternal val d = FourXDriver()\n");
        expect(root, listed, Set.of(), 1, "a core file using a 4.x name fails");
        expect(root, listed, Set.of(CORE + "\tFourXDriver"), 2, "a core file cannot be excused");
        write(root, CORE, "package p.core\ninternal class CoreThing\n");

        write(root, SHARED, "package p\nclass Mine { val d = fourXHelper() }\n");
        expect(root, listed, Set.of(), 1, "a shared file using a 4.x name fails");
        expect(root, listed, Set.of(SHARED + "\tfourXHelper"), 0, "an allow-listed shared use passes");
        write(root, SHARED, "package p\nclass Mine\n");
        expect(root, listed, Set.of(SHARED + "\tfourXHelper"), 1, "a stale allow-list entry fails");

        write(root, PKG + "Actual.kt", "package p\ninternal actual fun fourXHelper() {}\n");
        write(root, SHARED, "package p\nclass Mine { val d = fourXHelper() }\n");
        expect(root, listed, Set.of(), 0, "a name an unlisted file also declares is not a 4.x name");
        Files.delete(root.resolve(PKG + "Actual.kt"));

        expect(root, Set.of(DRIVER, PKG + "Gone.kt"), Set.of(SHARED + "\tfourXHelper"), 1, "a listed file that no longer exists fails");

        Path empty = Files.createTempDirectory("shared-out-of-4x-empty");
        expect(empty, Set.of(), Set.of(), 1, "an empty scan fails");
        System.out.println("SharedOutOf4xCheck selftest: every rule catches its planted violation, clean tree passes");
    }

    static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    static void expect(Path root, Set<String> listed, Set<String> allowlist, int failures, String what) throws IOException {
        List<String> got = run(root, listed, allowlist).failures;
        if (got.size() != failures) {
            System.err.println("selftest FAILED: " + what + ": expected " + failures + " failure(s), got " + got);
            System.exit(1);
        }
    }
}

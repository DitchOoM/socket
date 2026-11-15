import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The project's seven defaults, checked on the production sources of the run-to-completion QUIC core
 * (socket-quic-quiche/src/*Main/kotlin/com/ditchoom/socket/quic/core/**). A violation fails the build.
 *
 *     java scripts/CoreDefaultsCheck.java <repository root>
 *     java scripts/CoreDefaultsCheck.java --selftest
 *
 * Text rules on comment- and string-stripped Kotlin: no locks, no Boolean property, no nullable type, no message
 * exceptions, `throw` only in the edge, no ByteArray without the boundary suppression, no thread started from an
 * initializer, `suspend` only in the edge and resolver, and no buffer factory of the core's own: buffers come
 * from the factory the caller injected. Finding no file at all is a failure: a scan that matched
 * nothing must not look like a clean one.
 */
public class CoreDefaultsCheck {
    record Rule(String name, Pattern pattern, List<String> exemptPieces) {}

    record Finding(String file, int line, String rule, String text) {
        @Override
        public String toString() {
            return file + ":" + line + ": " + rule + ": " + text.strip();
        }
    }

    static final List<Rule> LINE_RULES = List.of(
        new Rule("lock", Pattern.compile(
            "\\bsynchronized\\s*\\(|@Synchronized\\b|\\bMutex\\b|\\bSemaphore\\b|\\bReentrant(ReadWrite)?Lock\\b"
                + "|\\bStampedLock\\b|\\bThreadPoolExecutor\\b|\\bExecutors\\."), List.of()),
        // A stored Boolean is state; a getter computed from sealed state is a predicate, which default 3 allows.
        new Rule("boolean-property", Pattern.compile(
            "\\bvar\\s+`?\\w+`?\\s*:\\s*Boolean\\b|\\bval\\s+`?\\w+`?\\s*:\\s*Boolean\\b(?!\\s*get\\s*\\()"), List.of()),
        new Rule("nullable", Pattern.compile(
            "[:<,(]\\s*[A-Za-z_][\\w.]*(<[^=;{}]*>)?\\s*\\?(?![:.?])|\\)\\s*\\?(?![:.?\\s]*[\\w(])|\\bas\\?|\\barrayOfNulls\\b"),
            List.of()),
        new Rule("message-exception", Pattern.compile(
            "\\berror\\s*\\(|\\b(require|check)\\s*\\(.*\\)\\s*\\{|\\b(requireNotNull|checkNotNull)\\b|\\bTODO\\s*\\("),
            List.of()),
        new Rule("throw-outside-edge", Pattern.compile("\\bthrow\\b"), List.of("edge")),
        new Rule("suspend-outside-edge", Pattern.compile("\\bsuspend\\b"), List.of("edge", "resolve")),
        new Rule("own-buffer-factory", Pattern.compile(
            "\\bBufferFactory\\.(Default\\b|managed\\(|shared\\(|deterministic\\(|network\\(|wrapNativeAddress\\()"
                + "|\\bPlatformBuffer\\.(allocate|wrap)"), List.of()),
        new Rule("thread-in-initializer", Pattern.compile(
            "^\\s*(private\\s+|internal\\s+|protected\\s+)?(val|var)\\s+\\w+[^=]*=.*(\\bthread\\s*[({]|\\.start\\(\\))"),
            List.of()));

    static final Pattern BYTE_ARRAY = Pattern.compile("\\bByteArray\\b");
    static final Pattern INIT_BLOCK = Pattern.compile("\\binit\\s*\\{");
    static final Pattern THREAD_START = Pattern.compile("\\.start\\(\\)|\\bthread\\s*[({]|startVirtualThread|\\.ofPlatform\\(|\\.ofVirtual\\(");
    static final String BOUNDARY = "@Suppress(\"NoByteArrayInProd\")";

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--selftest")) {
            selfTest();
            return;
        }
        if (args.length != 1) {
            System.err.println("usage: CoreDefaultsCheck <repository root> | --selftest");
            System.exit(2);
        }
        Path src = Path.of(args[0], "socket-quic-quiche", "src");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> sets = Files.list(src)) {
            for (Path set : sets.filter(p -> p.getFileName().toString().endsWith("Main")).toList()) {
                Path core = set.resolve("kotlin/com/ditchoom/socket/quic/core");
                if (!Files.isDirectory(core)) continue;
                try (Stream<Path> walk = Files.walk(core)) {
                    walk.filter(p -> p.toString().endsWith(".kt")).sorted().forEach(files::add);
                }
            }
        }
        if (files.isEmpty()) {
            System.err.println("CoreDefaultsCheck: no core source file found under " + src + "; refusing to pass an empty scan");
            System.exit(1);
        }
        List<Finding> findings = new ArrayList<>();
        for (Path file : files) {
            String piece = pieceOf(file);
            findings.addAll(check(src.relativize(file).toString(), piece, Files.readString(file)));
        }
        findings.forEach(System.out::println);
        System.out.println("CoreDefaultsCheck: " + files.size() + " files, " + findings.size() + " violations");
        if (!findings.isEmpty()) System.exit(1);
    }

    static String pieceOf(Path file) {
        Path at = file;
        while (at.getParent() != null && !at.getParent().getFileName().toString().equals("core")) at = at.getParent();
        return at.getParent() == null || at.equals(file) ? "" : at.getFileName().toString();
    }

    static List<Finding> check(String name, String piece, String source) {
        String[] raw = source.split("\n", -1);
        String[] code = strip(source).split("\n", -1);
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < code.length; i++) {
            String line = code[i];
            for (Rule rule : LINE_RULES) {
                if (rule.exemptPieces().contains(piece)) continue;
                if (rule.pattern().matcher(line).find()) findings.add(new Finding(name, i + 1, rule.name(), raw[i]));
            }
            if (BYTE_ARRAY.matcher(line).find() && !boundaryAbove(raw, i)) {
                findings.add(new Finding(name, i + 1, "byte-array", raw[i]));
            }
        }
        Matcher init = INIT_BLOCK.matcher(String.join("\n", code));
        String all = String.join("\n", code);
        while (init.find()) {
            int open = init.end() - 1;
            int close = matchingBrace(all, open);
            String body = all.substring(open, close);
            Matcher start = THREAD_START.matcher(body);
            if (start.find()) {
                int line = lineOf(all, open + start.start());
                findings.add(new Finding(name, line, "thread-in-initializer", raw[line - 1]));
            }
        }
        return findings;
    }

    static boolean boundaryAbove(String[] raw, int index) {
        for (int i = index; i >= Math.max(0, index - 3); i--) if (raw[i].contains(BOUNDARY)) return true;
        return false;
    }

    static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return i;
        }
        return text.length();
    }

    static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    /** Comments become blanks and string contents become nothing; line breaks are kept so line numbers hold. */
    static String strip(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 2;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                out.append("\"\"");
                i += 3;
                while (i < n && !source.startsWith("\"\"\"", i)) {
                    if (source.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 3;
            } else if (c == '"') {
                out.append("\"\"");
                i++;
                while (i < n && source.charAt(i) != '"' && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else if (c == '\'' && i + 2 < n && (source.charAt(i + 2) == '\'' || source.charAt(i + 1) == '\\')) {
                out.append("' '");
                i += source.charAt(i + 1) == '\\' ? 4 : 3;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static void selfTest() {
        Map<String, String> planted = Map.ofEntries(
            Map.entry("lock", "fun f() { synchronized(this) { } }"),
            Map.entry("boolean-property", "class A(val ready: Boolean) { var done: Boolean = false }"),
            Map.entry("nullable", "fun f(): String? = x"),
            Map.entry("message-exception", "fun f() { require(x > 0) { \"x must be positive\" } }"),
            Map.entry("throw-outside-edge", "fun f(): Nothing = throw IllegalStateException()"),
            Map.entry("suspend-outside-edge", "suspend fun f() = Unit"),
            Map.entry("thread-in-initializer", "class A { init { worker.start() } }"),
            Map.entry("byte-array", "fun f(b: ByteArray) = Unit"),
            Map.entry("own-buffer-factory", "val b = BufferFactory.Default.allocate(1500)"));
        int failures = 0;
        for (var entry : planted.entrySet()) {
            List<Finding> found = check("planted-" + entry.getKey() + ".kt", "loop", entry.getValue() + "\n");
            boolean caught = found.stream().anyMatch(f -> f.rule().equals(entry.getKey()));
            if (!caught) {
                System.err.println("SELFTEST: a planted " + entry.getKey() + " violation was not caught: " + found);
                failures++;
            }
        }
        String clean = String.join("\n",
            "/** synchronized, null, error(\"x\"), ByteArray and suspend in a comment are not code. */",
            "// var flag: Boolean = true",
            "fun ready(): Boolean = a ?: b",
            "fun first(): Int = list.firstOrNull()?.size ?: 0",
            "val label = \"String? error( suspend throw ByteArray\"",
            "fun start(loop: Loop) { thread.start() }",
            "@Suppress(\"NoByteArrayInProd\") // the java.net.InetAddress boundary",
            "fun bytes(): ByteArray = address.address",
            "when (x) { is A -> 1 else -> 2 }",
            "fun <T : Any> pick(items: List<T>): T = items[0]",
            "fun read(factory: BufferFactory) = factory.allocate(1500)",
            "override val isOpen: Boolean get() = use.load() !== Use.Closed",
            "");
        List<Finding> cleanFindings = check("clean.kt", "loop", clean);
        if (!cleanFindings.isEmpty()) {
            System.err.println("SELFTEST: clean code was flagged: " + cleanFindings);
            failures++;
        }
        List<Finding> edgeThrow = check("edge.kt", "edge", "fun f(): Nothing = throw CancellationException()\nsuspend fun g() = Unit\n");
        if (!edgeThrow.isEmpty()) {
            System.err.println("SELFTEST: the edge may throw and suspend, but was flagged: " + edgeThrow);
            failures++;
        }
        if (failures > 0) System.exit(1);
        System.out.println("CoreDefaultsCheck selftest: " + planted.size() + " planted violations caught, clean code passes");
    }
}

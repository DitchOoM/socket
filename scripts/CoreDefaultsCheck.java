import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 *
 * `--rules=all` adds the composition rules of the core design (section 11: D S W E O I B V F Q T P M C L); the
 * default `--rules=legacy` is the seven defaults only, because the old core tree is deleted in a later step.
 * `--selftest` exercises every rule of both sets.
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
        boolean all = false;
        boolean selftest = false;
        String root = null;
        for (String arg : args) {
            if (arg.equals("--selftest")) selftest = true;
            else if (arg.equals("--rules=all")) all = true;
            else if (arg.equals("--rules=legacy")) all = false;
            else if (root == null && !arg.startsWith("--")) root = arg;
            else root = "";
        }
        if (selftest && root == null) {
            selfTest();
            return;
        }
        if (root == null || root.isEmpty()) {
            System.err.println("usage: CoreDefaultsCheck [--rules=legacy|all] <repository root> | --selftest");
            System.exit(2);
        }
        Path src = Path.of(root, "socket-quic-quiche", "src");
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
        if (all) {
            List<Source> sources = new ArrayList<>();
            for (Path file : files) sources.add(new Source(src.relativize(file).toString(), Files.readString(file)));
            findings.addAll(analyzeAll(sources, true));
        } else {
            for (Path file : files) {
                String piece = pieceOf(file);
                findings.addAll(check(src.relativize(file).toString(), piece, Files.readString(file)));
            }
        }
        findings.forEach(System.out::println);
        if (all) {
            Map<String, Integer> perRule = new TreeMap<>();
            for (Finding f : findings) perRule.merge(f.rule(), 1, Integer::sum);
            perRule.forEach((rule, n) -> System.out.println("  rule " + rule + ": " + n));
        }
        System.out.println("CoreDefaultsCheck (" + (all ? "all" : "legacy") + " rules): " + files.size() + " files, "
            + findings.size() + " violations");
        if (!findings.isEmpty()) System.exit(1);
    }

    static String pieceOf(Path file) {
        Path at = file;
        while (at.getParent() != null && !at.getParent().getFileName().toString().equals("core")) at = at.getParent();
        return at.getParent() == null || at.equals(file) ? "" : at.getFileName().toString();
    }

    static List<Finding> check(String name, String piece, String source) {
        return check(name, piece, source, Set.of());
    }

    /** The seven defaults, line by line; `skip` names rules a caller replaces with a stronger one. */
    static List<Finding> check(String name, String piece, String source, Set<String> skip) {
        String[] raw = source.split("\n", -1);
        String[] code = strip(source).split("\n", -1);
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < code.length; i++) {
            String line = code[i];
            for (Rule rule : LINE_RULES) {
                if (rule.exemptPieces().contains(piece) || skip.contains(rule.name())) continue;
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
        findings.addAll(literalExceptions(name, raw, all));
        return findings;
    }

    static final Pattern EXCEPTION_CALL = Pattern.compile("\\b\\w*(Exception|Error)\\s*\\(");
    static final Pattern MESSAGE_ASSIGN = Pattern.compile("\\bmessage\\s*=(?!=)|\\boverride\\s+val\\s+message\\b");
    static final Pattern MESSAGE_READ = Pattern.compile("\\.message\\b");

    /**
     * An exception's meaning lives in its type. Strings are stripped to `""`, so a literal or template argument
     * of an exception or error constructor, a message assigned from one, and any `.message` read are visible here.
     */
    static List<Finding> literalExceptions(String name, String[] raw, String all) {
        List<Finding> findings = new ArrayList<>();
        Matcher call = EXCEPTION_CALL.matcher(all);
        while (call.find()) {
            int open = call.end() - 1;
            int close = matchingParen(all, open);
            if (all.substring(open, close).contains("\"\"")) {
                int line = lineOf(all, call.start());
                findings.add(new Finding(name, line, "message-exception-literal", raw[line - 1]));
            }
        }
        String[] lines = all.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher assign = MESSAGE_ASSIGN.matcher(lines[i]);
            if (assign.find()) {
                String tail = lines[i].substring(assign.start()) + (i + 1 < lines.length ? lines[i + 1] : "");
                if (tail.contains("\"\"")) findings.add(new Finding(name, i + 1, "message-exception-literal", raw[i]));
            }
            if (MESSAGE_READ.matcher(lines[i]).find()) {
                findings.add(new Finding(name, i + 1, "message-exception-literal", raw[i]));
            }
        }
        return findings;
    }

    static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return i;
        }
        return text.length();
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


    // ------------------------------------------------------------------------------------------------------------
    // The composition rules (`--rules=all`). Each rule is one static method over a parsed file; findings carry the
    // rule's letter. The two scanner additions: `collect` (declared names, first pass over every file) and
    // `walkFrames` (a brace walk that labels each `{` as a class body or something else).
    // ------------------------------------------------------------------------------------------------------------

    record Source(String path, String text) {}

    /** A `{ ... }` region. A class body is one whose header's last declaration keyword is class, object or interface. */
    static final class Frame {
        int open;
        int close;
        boolean classBody;
        int kwOffset = -1;
        ClassDecl decl;
    }

    record Decl(String kw, int offset, Frame frame) {}

    static final class ClassDecl {
        String kind;
        String name;
        int kwOffset;
        String params;
        boolean sealed;
        boolean privateSealed;
        boolean isEnum;
        List<String> supers = new ArrayList<>();
        Frame frame;
    }

    static final class Parsed {
        String path;
        String pkg;
        String base;
        String[] raw;
        String code;
        int[] lineStart;
        boolean[] inParens;
        List<Frame> frames = new ArrayList<>();
        List<Decl> decls = new ArrayList<>();
        List<ClassDecl> classes = new ArrayList<>();

        int lineAt(int offset) {
            int lo = 0;
            int hi = lineStart.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (lineStart[mid] <= offset) lo = mid;
                else hi = mid - 1;
            }
            return lo + 1;
        }

        String lineText(int line) {
            return code.split("\n", -1)[line - 1];
        }

        Frame frameAt(int offset) {
            Frame best = null;
            for (Frame f : frames) if (f.open < offset && offset < f.close) best = f;
            return best;
        }
    }

    /** Names gathered over every file before any rule runs. */
    static final class Names {
        final Set<String> enumEntries = new HashSet<>(EXTERNAL_ENUM_ENTRIES);
        final Set<String> valueClasses = new HashSet<>(QUICHE_VALUE_TYPES);
        final Set<String> sealed = new HashSet<>();
        final Set<String> privateSealed = new HashSet<>();
        final Set<String> phaseVariants = new HashSet<>();
        final Set<String> openTypes = new HashSet<>();
    }

    /** Core packages and the packages each may import (section 2). `observe` is importable by every package. */
    static final Map<String, Set<String>> ADJACENCY = new LinkedHashMap<>();

    static {
        ADJACENCY.put("pool", Set.of());
        ADJACENCY.put("time", Set.of());
        ADJACENCY.put("observe", Set.of());
        ADJACENCY.put("udp", Set.of("pool"));
        ADJACENCY.put("ready", Set.of("time"));
        ADJACENCY.put("life", Set.of("udp", "time"));
        ADJACENCY.put("sched", Set.of("life", "udp", "ready", "pool", "time"));
        ADJACENCY.put("stream", Set.of("life", "pool"));
        ADJACENCY.put("path", Set.of("life", "udp", "time"));
        ADJACENCY.put("conn", Set.of("stream", "path", "life", "sched", "udp", "pool", "time"));
        ADJACENCY.put("demux", Set.of("conn", "life", "sched", "udp", "pool", "time"));
        ADJACENCY.put("loop", Set.of("demux", "conn", "life", "sched", "ready", "udp", "pool", "time"));
        ADJACENCY.put("edge", Set.copyOf(List.of("pool", "time", "observe", "udp", "ready", "life", "sched", "stream",
            "path", "conn", "demux", "loop", "edge")));
    }

    /** The complete allowlist of non-sealed interfaces and open or abstract classes (section 4.3). QuicheApi lives outside core. */
    static final Map<String, String> SEAMS = new LinkedHashMap<>();

    static {
        SEAMS.put("QuicheApi", "");
        SEAMS.put("LoopClock", "time");
        SEAMS.put("DatagramBatchIo", "udp");
        SEAMS.put("Poller", "ready");
        SEAMS.put("InboxItem", "sched");
        SEAMS.put("LoopWork", "sched");
        SEAMS.put("ConnWork", "conn");
        SEAMS.put("Waiter", "stream");
        SEAMS.put("ConnSink", "conn");
        SEAMS.put("Pooled", "pool");
    }

    /** Value classes that live outside core (QuicheHandles.kt, StreamRecvStep.kt, DeadlineQueue.kt). */
    static final Set<String> QUICHE_VALUE_TYPES = Set.of("QuicheConfig", "QuicheConn", "QuicheRecvInfo",
        "QuicheSendInfo", "QuicheStreamIter", "NextDue", "NextStream", "StreamRecvStep");

    /** Entries of enums declared outside core (`QuicRole`, `LinkClass`), so `QuicRole.Client ->` is not an object compared by equals. */
    static final Set<String> EXTERNAL_ENUM_ENTRIES = Set.of("Client", "Server", "Ethernet", "Wifi", "Cellular", "Satellite", "Unknown");

    /** `when` over an open platform type (Throwable, IOException) may keep its `else`. */
    static final List<String> OPEN_WHEN_FILES = List.of("udp/*Errors.kt", "edge/Failures.kt", "life/Tiers.kt");

    /** Files that may carry an intrusive `@Link` var, written by their queue. */
    static final List<String> LINK_FILES = List.of("sched/WorkInbox.kt", "sched/LoopPort.kt", "sched/CallerLanes.kt");

    static final List<String> WIRING_FILES = List.of("Turn.kt", "Compose.kt", "Slots.kt", "Rules.kt");

    /** Where a `catch` may sit: the three containment sites, plus the platform mapping in `udp`. */
    static final List<String> CATCH_SITES = List.of("edge/OpGuard.kt", "conn/Turn.kt", "loop/Loop.kt", "udp/*");

    static final List<String> OWNERSHIP_KINDS = List.of("@Confined", "@LoopOnly", "@Published", "@Queue", "@Pool", "@CallerSide");

    /** Quiche function (regex alternatives) to the only files allowed to call it (section 3). `pkg/BasePrefix`. */
    static final Map<String, List<String>> QUICHE_OWNERS = new LinkedHashMap<>();

    static {
        QUICHE_OWNERS.put("connRecv", List.of("conn/Ingress"));
        QUICHE_OWNERS.put("connSend|sendInfoAtNanos|sendInfoFromAddr|sendInfoFromAddrLen|sendInfoToAddr|sendInfoToAddrLen",
            List.of("conn/Egress"));
        QUICHE_OWNERS.put("connLossEvidence|connRecvGaps", List.of("conn/AckHold", "conn/AckCadence"));
        QUICHE_OWNERS.put("connTimeout|connOnTimeout", List.of("conn/ConnTimer"));
        QUICHE_OWNERS.put("connIsEstablished|connIsHandshakeConfirmed|connSetHandshakePtoCeiling|connApplicationProto|configSetInitialRtt",
            List.of("conn/Handshake"));
        QUICHE_OWNERS.put("connClose|connIsClosed|connIsTimedOut|connPeerError|connLocalError", List.of("life/ConnLife"));
        QUICHE_OWNERS.put("connStreamDataUnacknowledged", List.of("life/Linger", "life/ConnLife"));
        QUICHE_OWNERS.put("connFree|recvInfoFree|sendInfoFree", List.of("conn/Release"));
        QUICHE_OWNERS.put("connReadable|connWritable|streamIterFree|streamIterNext|streamIterNextId|connPeerStreamsLeftBidi|connPeerStreamsLeftUni",
            List.of("stream/StreamTable"));
        QUICHE_OWNERS.put("configSetInitialMaxData|configSetInitialMaxStreamDataBidiLocal|configSetInitialMaxStreamDataBidiRemote"
            + "|configSetInitialMaxStreamDataUni|configSetInitialMaxStreamsBidi|configSetInitialMaxStreamsUni"
            + "|configSetMaxConnectionWindow|configSetMaxStreamWindow", List.of("stream/FlowWindows"));
        QUICHE_OWNERS.put("connStreamSend|connStreamRecvStep|connStreamShutdown|connStreamCapacity", List.of("stream/StreamIo"));
        QUICHE_OWNERS.put("connProbePath|connMigrate|connMigrateSource|connNewScid|connRetireDcid|connPathEventNext|connPathStats"
            + "|recvInfoNew|sendInfoNew", List.of("path/Paths"));
        QUICHE_OWNERS.put("connSetSession|connSession|connIsResumed|connIsInEarlyData|connEarlyDataReason", List.of("conn/Resumption"));
        QUICHE_OWNERS.put("connDgramSend|connDgramRecv|connDgramMaxWritableLen|hasReadableDgram", List.of("conn/Datagrams"));
        QUICHE_OWNERS.put("headerInfo", List.of("demux/Demux"));
        QUICHE_OWNERS.put("accept", List.of("demux/Admission"));
        QUICHE_OWNERS.put("negotiateVersion", List.of("demux/Stateless"));
        QUICHE_OWNERS.put("retry", List.of("demux/Stateless"));
    }

    static final Set<String> GENERIC_QUICHE_NAMES = Set.of("accept", "retry");

    static final Pattern CORE_REF = Pattern.compile("com\\.ditchoom\\.socket\\.quic\\.core\\.([a-z]\\w*)\\.");
    static final Pattern DECL_WORD = Pattern.compile("\\b(var|val|fun)\\b");
    static final Pattern CLASS_WORD = Pattern.compile("(?<!::)\\b(class|object|interface)\\s+([A-Za-z_]\\w*)");
    static final Pattern HEADER_KEYWORD = Pattern.compile("(?<!::)\\b(class|object|interface|fun)\\b");
    static final Pattern UPPER_SNAKE = Pattern.compile("[A-Z][A-Z0-9_]*");
    static final Pattern CAP_PATH = Pattern.compile("[A-Z]\\w*(\\.[A-Za-z_]\\w*)*");

    static String pkgOf(String path) {
        String p = "/" + path.replace('\\', '/');
        int at = p.indexOf("/core/");
        if (at < 0) return "";
        String rest = p.substring(at + 6);
        int slash = rest.indexOf('/');
        return slash < 0 ? "" : rest.substring(0, slash);
    }

    static String baseOf(String path) {
        return path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
    }

    /** `pkg/File.kt` exactly, or a pattern with `*` standing for any run of characters inside one path (`udp/*Errors.kt`, `udp/*`). */
    static boolean pathMatches(Parsed p, List<String> patterns) {
        String here = p.pkg + "/" + p.base;
        for (String pattern : patterns) {
            String regex = ("\\Q" + pattern + "\\E").replace("*", "\\E[^/]*\\Q");
            if (here.matches(regex)) return true;
        }
        return false;
    }

    static Parsed parse(Source source) {
        Parsed p = new Parsed();
        p.path = source.path();
        p.pkg = pkgOf(source.path());
        p.base = baseOf(source.path());
        p.raw = source.text().split("\n", -1);
        p.code = strip(source.text());
        String code = p.code;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < code.length(); i++) if (code.charAt(i) == '\n') starts.add(i + 1);
        p.lineStart = starts.stream().mapToInt(Integer::intValue).toArray();
        p.inParens = new boolean[code.length() + 1];
        walkFrames(p);
        Matcher cm = CLASS_WORD.matcher(code);
        while (cm.find()) p.classes.add(classDecl(p, cm));
        Matcher dm = DECL_WORD.matcher(code);
        while (dm.find()) {
            if (p.inParens[dm.start()]) continue;
            p.decls.add(new Decl(dm.group(1), dm.start(), p.frameAt(dm.start())));
        }
        for (ClassDecl d : p.classes) {
            for (Frame f : p.frames) if (f.kwOffset == d.kwOffset) d.frame = f;
            if (d.frame != null) d.frame.decl = d;
        }
        return p;
    }

    /** The brace walk: every `{` is labelled a class body or not, and `inParens` marks text inside parentheses. */
    static void walkFrames(Parsed p) {
        String code = p.code;
        Deque<Frame> stack = new ArrayDeque<>();
        Deque<Integer> parens = new ArrayDeque<>();
        int paren = 0;
        int boundary = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') paren++;
            if (c == ')') paren = Math.max(0, paren - 1);
            p.inParens[i] = paren > 0;
            if (c == '{') {
                Frame f = new Frame();
                f.open = i;
                f.close = code.length();
                String lastKw = "";
                int lastAt = -1;
                Matcher m = HEADER_KEYWORD.matcher(code.substring(boundary, i));
                while (m.find()) {
                    lastKw = m.group(1);
                    lastAt = m.start();
                }
                if (lastAt >= 0 && !lastKw.equals("fun")) {
                    f.classBody = true;
                    f.kwOffset = boundary + lastAt;
                }
                p.frames.add(f);
                stack.push(f);
                parens.push(paren);
                paren = 0;
                boundary = i + 1;
            } else if (c == '}') {
                if (!stack.isEmpty()) {
                    stack.pop().close = i;
                    paren = parens.pop();
                }
                boundary = i + 1;
            } else if (c == ';' && paren == 0) {
                boundary = i + 1;
            }
        }
    }

    static ClassDecl classDecl(Parsed p, Matcher m) {
        String code = p.code;
        ClassDecl d = new ClassDecl();
        d.kind = m.group(1);
        d.name = m.group(2);
        d.kwOffset = m.start();
        int lineStart = code.lastIndexOf('\n', m.start()) + 1;
        String prefix = code.substring(lineStart, m.start());
        d.sealed = prefix.matches(".*\\bsealed\\s+(fun\\s+)?");
        d.privateSealed = d.sealed && prefix.contains("private");
        d.isEnum = prefix.matches(".*\\benum\\s+");
        int at = m.end();
        while (at < code.length() && Character.isWhitespace(code.charAt(at))) at++;
        if (at < code.length() && code.charAt(at) == '<') at = matchingAngle(code, at) + 1;
        Matcher ctor = Pattern.compile("\\G\\s*(?:(?:private|internal|protected|public)\\s+)?(?:constructor\\s*)?\\(").matcher(code);
        if (ctor.region(at, code.length()).find()) {
            int open = ctor.end() - 1;
            int close = matchingParen(code, open);
            d.params = code.substring(open + 1, Math.min(close, code.length()));
            at = Math.min(close + 1, code.length());
        }
        int end = at;
        while (end < code.length() && code.charAt(end) != '{' && code.charAt(end) != '\n') end++;
        String tail = code.substring(at, end);
        int colon = tail.indexOf(':');
        if (colon >= 0) {
            Matcher w = Pattern.compile("[A-Za-z_]\\w*").matcher(tail.substring(colon + 1).replaceAll("<[^<>]*>", ""));
            while (w.find()) if (!w.group().equals("by") && !w.group().equals("where")) d.supers.add(w.group());
        }
        return d;
    }

    static int matchingAngle(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '<') depth++;
            if (text.charAt(i) == '>' && --depth == 0) return i;
        }
        return text.length();
    }

    /** The first pass: enum entries, value classes, sealed types, phase instances and declared open types. */
    static Names collect(List<Parsed> files) {
        Names n = new Names();
        for (Parsed p : files) {
            Matcher vc = Pattern.compile("\\bvalue\\s+class\\s+(\\w+)").matcher(p.code);
            while (vc.find()) n.valueClasses.add(vc.group(1));
            for (ClassDecl d : p.classes) {
                if (d.sealed) n.sealed.add(d.name);
                if (d.privateSealed) n.privateSealed.add(d.name);
                if (d.isEnum && d.frame != null) collectEntries(p, d.frame, n.enumEntries);
            }
        }
        for (Parsed p : files) {
            for (ClassDecl d : p.classes) {
                boolean extendsSealed = d.supers.stream().anyMatch(n.sealed::contains);
                if (extendsSealed && d.frame != null && hasDirect(p, d.frame, "var")) n.phaseVariants.add(d.name);
            }
        }
        return n;
    }

    static void collectEntries(Parsed p, Frame body, Set<String> into) {
        String text = p.code.substring(body.open + 1, Math.min(body.close, p.code.length()));
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ';';
            if ("({[<".indexOf(c) >= 0) depth++;
            if (")}]>".indexOf(c) >= 0) depth--;
            if (depth == 0 && (c == ',' || c == ';')) {
                Matcher name = Pattern.compile("^\\s*(?:@\\w+\\s+)*([A-Za-z_]\\w*)").matcher(text.substring(start, i));
                if (name.find()) into.add(name.group(1));
                if (c == ';') return;
                start = i + 1;
            }
        }
    }

    static boolean hasDirect(Parsed p, Frame f, String kw) {
        for (Decl d : p.decls) if (d.frame == f && d.kw().equals(kw)) return true;
        return false;
    }

    static boolean variantOfPrivateSealed(ClassDecl d, Names n) {
        return d != null && d.supers.stream().anyMatch(n.privateSealed::contains);
    }

    static void add(List<Finding> out, Parsed p, int line, String rule) {
        out.add(new Finding(p.path, line, rule, p.raw[Math.min(line, p.raw.length) - 1]));
    }

    /** Every rule of the design's section 11, plus the seven defaults except the throw rule that F subsumes. */
    static List<Finding> analyzeAll(List<Source> sources, boolean wholeTree) {
        List<Parsed> files = new ArrayList<>();
        for (Source s : sources) files.add(parse(s));
        Names names = collect(files);
        List<Finding> out = new ArrayList<>();
        for (Parsed p : files) {
            for (Finding f : check(p.path, p.pkg, String.join("\n", p.raw), Set.of("throw-outside-edge"))) {
                String rule = f.rule().equals("boolean-property") ? "B" : f.rule().equals("nullable") ? "N" : f.rule();
                out.add(new Finding(f.file(), f.line(), rule, f.text()));
            }
            ruleDirection(p, out);
            ruleStatePrivacy(p, names, out);
            ruleWiring(p, out);
            ruleElseAndObjects(p, names, out);
            ruleSeams(p, out);
            ruleBooleanState(p, out);
            ruleValueBoxing(p, names, out);
            ruleFailures(p, out);
            ruleQuicheOwnership(p, out);
            ruleOwnershipKind(p, names, out);
            rulePhaseInstances(p, names, out);
            ruleMagicNumbers(p, out);
            ruleCasts(p, out);
            ruleSize(p, out);
        }
        if (wholeTree) missingSeams(files, out);
        return out;
    }

    /** D: an import the adjacency table does not allow. Comments and strings are already stripped. */
    static void ruleDirection(Parsed p, List<Finding> out) {
        if (!ADJACENCY.containsKey(p.pkg)) return;
        Matcher m = CORE_REF.matcher(p.code);
        while (m.find()) {
            String target = m.group(1);
            if (!ADJACENCY.containsKey(target) || target.equals(p.pkg) || target.equals("observe") || p.pkg.equals("edge")) continue;
            if (!ADJACENCY.get(p.pkg).contains(target)) add(out, p, p.lineAt(m.start()), "D");
        }
    }

    /** S: state writable from outside its owner. */
    static void ruleStatePrivacy(Parsed p, Names n, List<Finding> out) {
        for (Decl d : p.decls) {
            if (d.frame() == null || !d.frame().classBody || d.kw().equals("fun")) continue;
            if (variantOfPrivateSealed(d.frame().decl, n)) continue;
            int line = p.lineAt(d.offset());
            String before = p.code.substring(p.lineStart[line - 1], d.offset());
            String lineText = p.lineText(line);
            boolean isPrivate = before.matches(".*\\bprivate\\b.*");
            if (d.kw().equals("var")) {
                boolean privateSet = false;
                for (int l = line; l <= Math.min(line + 2, p.raw.length); l++) privateSet |= p.lineText(l).contains("private set");
                boolean link = (before + (line > 1 ? p.lineText(line - 1) : "")).contains("@Link") && pathMatches(p, LINK_FILES);
                if (!isPrivate && !privateSet && !link) add(out, p, line, "S");
            } else if (!isPrivate && lineText.substring(Math.max(0, d.offset() - p.lineStart[line - 1])).matches(
                ".*=\\s*(?:\\w*Array\\s*[(<]|arrayOf\\s*\\(|\\w*ArrayOf\\s*\\(|(?:Array|Hash|LinkedHash|Linked|Concurrent)\\w*\\s*[(<]"
                    + "|mutable\\w+Of\\s*[(<]|ArrayDeque\\s*[(<]|Atomic\\w+\\s*[(<]).*")) {
                add(out, p, line, "S");
            }
        }
        for (ClassDecl c : p.classes) {
            if (c.params == null || variantOfPrivateSealed(c, n)) continue;
            Matcher v = Pattern.compile("\\bvar\\b").matcher(c.params);
            while (v.find()) {
                int comma = c.params.lastIndexOf(',', v.start());
                if (!c.params.substring(comma + 1, v.start()).contains("private")) add(out, p, p.lineAt(c.kwOffset), "S");
            }
        }
    }

    /** W: no two-phase wiring, no state in wiring files, no component shared between two owners. */
    static void ruleWiring(Parsed p, List<Finding> out) {
        Matcher m = Pattern.compile("\\b(lateinit|typealias)\\b").matcher(p.code);
        while (m.find()) add(out, p, p.lineAt(m.start()), "W");
        if (!WIRING_FILES.contains(p.base)) return;
        Matcher v = Pattern.compile("\\bvar\\b").matcher(p.code);
        while (v.find()) add(out, p, p.lineAt(v.start()), "W");
        if (!p.base.equals("Compose.kt")) return;
        for (Decl d : p.decls) {
            if (!d.kw().equals("val") || d.frame() == null || d.frame().classBody) continue;
            Matcher name = Pattern.compile("\\Gval\\s+`?(\\w+)`?\\s*[=:]").matcher(p.code);
            if (!name.region(d.offset(), p.code.length()).find()) continue;
            int from = p.code.indexOf('\n', d.offset());
            if (from < 0) continue;
            String rest = p.code.substring(from, Math.min(d.frame().close, p.code.length()));
            Matcher use = Pattern.compile("\\b" + Pattern.quote(name.group(1)) + "\\b").matcher(rest);
            int uses = 0;
            while (use.find()) uses++;
            if (uses > 1) add(out, p, p.lineAt(d.offset()), "W");
        }
    }

    record Branch(int offset, boolean isElse, List<String> items) {}

    static List<Branch> branches(String code, int open, int close) {
        String body = code.substring(open + 1, Math.min(close, code.length()));
        List<Branch> out = new ArrayList<>();
        int depth = 0;
        int prevEnd = 0;
        int lastNl = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if ("({[".indexOf(c) >= 0) depth++;
            else if (")}]".indexOf(c) >= 0) depth--;
            else if (depth == 0 && c == '\n') {
                if (!body.substring(prevEnd, i).stripTrailing().endsWith(",")) lastNl = i;
            } else if (depth == 0 && c == '-' && i + 1 < body.length() && body.charAt(i + 1) == '>') {
                String segment = body.substring(prevEnd, i);
                String cond = lastNl >= prevEnd ? body.substring(lastNl + 1, i) : segment;
                boolean isElse = segment.stripTrailing().matches("(?s).*(^|\\s)else");
                out.add(new Branch(open + 1 + i, isElse, isElse ? List.of() : splitItems(cond)));
                prevEnd = i + 2;
                lastNl = -1;
                i++;
            }
        }
        return out;
    }

    static List<String> splitItems(String cond) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= cond.length(); i++) {
            char c = i < cond.length() ? cond.charAt(i) : ',';
            if ("({[".indexOf(c) >= 0) depth++;
            if (")}]".indexOf(c) >= 0) depth--;
            if (depth == 0 && c == ',') {
                items.add(cond.substring(start, i).strip());
                start = i + 1;
            }
        }
        return items;
    }

    static boolean isTypeTest(String item) {
        return item.matches("(?s)!?is\\b.*");
    }

    static boolean isCapPath(String item) {
        return CAP_PATH.matcher(item).matches() && !UPPER_SNAKE.matcher(item).matches();
    }

    /** E: `else` in a `when` over a sealed or enum type. O: an object variant matched by equals; a data-less class variant. */
    static void ruleElseAndObjects(Parsed p, Names n, List<Finding> out) {
        Matcher w = Pattern.compile("\\bwhen\\s*\\(").matcher(p.code);
        while (w.find()) {
            int close = matchingParen(p.code, w.end() - 1);
            int open = close + 1;
            while (open < p.code.length() && Character.isWhitespace(p.code.charAt(open))) open++;
            if (open >= p.code.length() || p.code.charAt(open) != '{') continue;
            List<Branch> bs = branches(p.code, open, matchingBrace(p.code, open));
            boolean closedKind = false;
            for (Branch b : bs) {
                for (String item : b.items()) {
                    if (isTypeTest(item) || isCapPath(item)) closedKind = true;
                    if (!isTypeTest(item) && isCapPath(item)) {
                        String last = item.substring(item.lastIndexOf('.') + 1);
                        if (!n.enumEntries.contains(last)) add(out, p, p.lineAt(b.offset()), "O");
                    }
                }
            }
            if (closedKind && !pathMatches(p, OPEN_WHEN_FILES)) {
                for (Branch b : bs) if (b.isElse()) add(out, p, p.lineAt(b.offset()), "E");
            }
        }
        for (ClassDecl d : p.classes) {
            if (!d.kind.equals("class") || d.params != null || d.sealed || d.isEnum) continue;
            Frame inside = p.frameAt(d.kwOffset);
            if (inside == null || inside.decl == null || !inside.decl.sealed || !d.supers.stream().anyMatch(s -> s.equals(inside.decl.name))) continue;
            int end = d.kwOffset;
            while (end < p.code.length() && p.code.charAt(end) != '\n' && p.code.charAt(end) != '{') end++;
            boolean hasBody = end < p.code.length() && p.code.charAt(end) == '{';
            if (!hasBody) add(out, p, p.lineAt(d.kwOffset), "O");
        }
    }

    /** I: open types outside the seam allowlist; members on sealed slot types. */
    static void ruleSeams(Parsed p, List<Finding> out) {
        Matcher m = Pattern.compile("(?<!sealed )\\b(?:fun\\s+)?interface\\s+(\\w+)|\\b(?:open|abstract)\\s+class\\s+(\\w+)").matcher(p.code);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            String before = p.code.substring(Math.max(0, m.start() - 8), m.start());
            if (before.matches("(?s).*\\bsealed\\s*")) continue;
            if (!SEAMS.containsKey(name)) add(out, p, p.lineAt(m.start()), "I");
        }
        for (Decl d : p.decls) {
            Frame f = d.frame();
            if (f != null && f.classBody && f.decl != null && f.decl.sealed && f.decl.kind.equals("interface")) {
                add(out, p, p.lineAt(d.offset()), "I");
            }
        }
    }

    /** The other half of I: every allowlisted seam must be declared somewhere in core. */
    static void missingSeams(List<Parsed> files, List<Finding> out) {
        Set<String> found = new HashSet<>();
        Set<String> built = new HashSet<>();
        for (Parsed p : files) {
            built.add(p.pkg);
            Matcher m = Pattern.compile("(?<!sealed )\\b(?:fun\\s+)?interface\\s+(\\w+)|\\b(?:open|abstract)\\s+class\\s+(\\w+)").matcher(p.code);
            while (m.find()) found.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        for (Map.Entry<String, String> seam : SEAMS.entrySet()) {
            // A seam of a package the rebuild has not reached yet is not missing, the package is.
            if (seam.getValue().isEmpty() || found.contains(seam.getKey()) || !built.contains(seam.getValue())) continue;
            out.add(new Finding("<seams>", 0, "I", "allowlisted seam " + seam.getKey() + " is declared nowhere in core"));
        }
    }

    /** B: stored Booleans beyond the typed ones the line rule sees. */
    static void ruleBooleanState(Parsed p, List<Finding> out) {
        Matcher a = Pattern.compile("\\bAtomicBoolean\\b").matcher(p.code);
        while (a.find()) add(out, p, p.lineAt(a.start()), "B");
        for (Decl d : p.decls) {
            if (d.kw().equals("fun") || d.frame() == null || !d.frame().classBody) continue;
            Matcher m = Pattern.compile("\\G(?:val|var)\\s+`?\\w+`?\\s*=\\s*(?:true|false)\\b").matcher(p.code);
            if (m.region(d.offset(), p.code.length()).find()) add(out, p, p.lineAt(d.offset()), "B");
        }
    }

    /** V: a value class used as a type argument or in a function type boxes on every use. */
    static void ruleValueBoxing(Parsed p, Names n, List<Finding> out) {
        String[] lines = p.code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (String name : n.valueClasses) {
                if (lines[i].matches(".*<[^>]*\\b" + name + "\\b[^>]*>.*") || lines[i].matches(".*\\(\\s*" + name + "\\s*\\)\\s*->.*")) {
                    if (lines[i].matches(".*\\bvalue\\s+class\\s+" + name + "\\b.*")) continue;
                    add(out, p, i + 1, "V");
                    break;
                }
            }
        }
    }

    /** F: meaning lives in the end, not in `cause`; one mapping builds the caller-facing failure; three catch sites. */
    static void ruleFailures(Parsed p, List<Finding> out) {
        Matcher cause = Pattern.compile("\\.cause\\b|\\b(?:val|var)\\s+cause\\b").matcher(p.code);
        while (cause.find()) add(out, p, p.lineAt(cause.start()), "F");
        boolean failures = p.pkg.equals("edge") && p.base.equals("Failures.kt");
        if (!failures) {
            // A call on a receiver (`api.connPeerError(h)`) is a method, not a constructor.
            Matcher m = Pattern.compile("(?<![.\\w])\\w*(?:Exception|Error)\\s*\\(|\\bthrow\\b|\\bresumeWithException\\b").matcher(p.code);
            while (m.find()) add(out, p, p.lineAt(m.start()), "F");
        } else {
            // A connection's end is what a close exception reports; a stream's own failure has no end to begin with.
            Matcher m = Pattern.compile("(?<!class )\\bQuicCloseException\\s*\\(").matcher(p.code);
            while (m.find()) {
                int open = m.end() - 1;
                String args = p.code.substring(open + 1, Math.min(matchingParen(p.code, open), p.code.length()));
                if (!args.matches("(?s)\\s*end\\b.*")) add(out, p, p.lineAt(m.start()), "F");
            }
        }
        if (!pathMatches(p, CATCH_SITES)) {
            Matcher c = Pattern.compile("\\bcatch\\s*\\(").matcher(p.code);
            while (c.find()) add(out, p, p.lineAt(c.start()), "F");
        }
    }

    /** Q: a quiche call outside the one file that decides it. */
    static void ruleQuicheOwnership(Parsed p, List<Finding> out) {
        for (Map.Entry<String, List<String>> owner : QUICHE_OWNERS.entrySet()) {
            boolean allowed = false;
            String here = p.pkg + "/" + p.base;
            for (String prefix : owner.getValue()) allowed |= here.startsWith(prefix);
            if (allowed) continue;
            // `accept` and `retry` are ordinary words (Consumer.accept): only a call on the quiche api counts.
            String receiver = GENERIC_QUICHE_NAMES.contains(owner.getKey()) ? "(?i:api|quiche)" : "";
            Matcher m = Pattern.compile(receiver + "\\.(?:" + owner.getKey() + ")\\s*\\(").matcher(p.code);
            while (m.find()) add(out, p, p.lineAt(m.start()), "Q");
        }
    }

    static String annotationsAbove(Parsed p, int kwOffset) {
        int line = p.lineAt(kwOffset);
        StringBuilder sb = new StringBuilder(p.code.substring(p.lineStart[line - 1], kwOffset));
        for (int l = line - 1; l >= 1 && p.lineText(l).strip().startsWith("@"); l--) sb.append(' ').append(p.lineText(l));
        return sb.toString();
    }

    /** T: a class with a var or an atomic carries one of the six ownership annotations; `@Published` holds one atomic and no var. */
    static void ruleOwnershipKind(Parsed p, Names n, List<Finding> out) {
        for (ClassDecl d : p.classes) {
            if (d.kind.equals("interface") || variantOfPrivateSealed(d, n)) continue;
            int vars = 0;
            int atomics = 0;
            if (d.params != null) {
                Matcher v = Pattern.compile("\\bvar\\b").matcher(d.params);
                while (v.find()) vars++;
            }
            if (d.frame != null) {
                for (Decl x : p.decls) if (x.frame() == d.frame && x.kw().equals("var")) vars++;
                String body = directBody(p, d.frame);
                Matcher a = Pattern.compile("\\bAtomic\\w+").matcher(body);
                while (a.find()) atomics++;
            }
            if (vars == 0 && atomics == 0) continue;
            String ann = annotationsAbove(p, d.kwOffset);
            boolean annotated = OWNERSHIP_KINDS.stream().anyMatch(k -> ann.matches("(?s).*" + k + "\\b.*"));
            boolean published = ann.contains("@Published");
            if (!annotated || (published && (vars > 0 || atomics > 1))) add(out, p, p.lineAt(d.kwOffset), "T");
        }
    }

    /** The frame's own text with nested braces removed. */
    static String directBody(Parsed p, Frame f) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = f.open + 1; i < Math.min(f.close, p.code.length()); i++) {
            char c = p.code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (depth == 0) sb.append(c);
        }
        return sb.toString();
    }

    /** P: a state-machine phase (a sealed variant with a var) is built once, into a `private val` of its owner. */
    static void rulePhaseInstances(Parsed p, Names n, List<Finding> out) {
        for (String name : n.phaseVariants) {
            Matcher m = Pattern.compile("(?<!class )(?<!object )\\b" + Pattern.quote(name) + "\\s*\\(").matcher(p.code);
            while (m.find()) {
                int line = p.lineAt(m.start());
                Frame f = p.frameAt(m.start());
                boolean ok = f != null && f.classBody && p.lineText(line).matches(".*\\bprivate\\s+val\\s+\\w+\\s*=.*");
                if (!ok) add(out, p, line, "P");
            }
        }
    }

    /** M: a sentinel standing for "none". Quiche's C result codes live in stream/QuicheCodes.kt, which is exempt. */
    static void ruleMagicNumbers(Parsed p, List<Finding> out) {
        if (p.pkg.equals("stream") && p.base.equals("QuicheCodes.kt")) return;
        Matcher m = Pattern.compile("\\b(?:Long|Int)\\.(?:MIN|MAX)_VALUE\\b|(?:=|==|!=|\\breturn|\\(|,)\\s*-1L?\\b").matcher(p.code);
        while (m.find()) add(out, p, p.lineAt(m.start()), "M");
    }

    /** C: a buffer or type assumed instead of proven. Only udp/NativeBytes.kt looks inside a buffer. */
    static void ruleCasts(Parsed p, List<Finding> out) {
        if (p.pkg.equals("udp") && p.base.equals("NativeBytes.kt")) return;
        Matcher m = Pattern.compile("\\bas\\s+[A-Z]|!!").matcher(p.code);
        while (m.find()) add(out, p, p.lineAt(m.start()), "C");
    }

    /** L: a file over 300 lines. */
    static void ruleSize(Parsed p, List<Finding> out) {
        String text = String.join("\n", p.raw);
        int lines = text.isEmpty() ? 0 : (int) text.chars().filter(c -> c == '\n').count() + (text.endsWith("\n") ? 0 : 1);
        if (lines > 300) out.add(new Finding(p.path, 301, "L", lines + " lines"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Self-test. Every "must fire" cell of the design's rule table is a planted case that THAT rule must catch;
    // every "must not fire" cell is a clean case that must pass. Planted cases that depend on where a file lives
    // carry a virtual path under core/<package>/.
    // ------------------------------------------------------------------------------------------------------------

    /** `files` alternates virtual path and source. `wholeTree` also runs the "every seam is declared" check. */
    record Case(String rule, boolean planted, boolean wholeTree, String... files) {
        List<Source> sources() {
            List<Source> out = new ArrayList<>();
            for (int i = 0; i < files.length; i += 2) out.add(new Source("core/" + files[i], files[i + 1] + "\n"));
            return out;
        }
    }

    static final List<Case> CASES = new ArrayList<>();

    static void planted(String rule, String path, String source) {
        CASES.add(new Case(rule, true, false, path, source));
    }

    static void clean(String rule, String path, String source) {
        CASES.add(new Case(rule, false, false, path, source));
    }

    static void cases() {
        String seamsDeclared = String.join("\n", SEAMS.keySet().stream().map(n -> "internal interface " + n).toList());
        // D direction
        planted("D", "stream/StreamTable.kt", "import com.ditchoom.socket.quic.core.edge.ConnectionReleased");
        planted("D", "conn/Conn.kt", "val loop = com.ditchoom.socket.quic.core.loop.Loop()");
        CASES.add(new Case("D", true, false,
            "stream/Table.kt", "import com.ditchoom.socket.quic.core.conn.Conn",
            "conn/Conn.kt", "import com.ditchoom.socket.quic.core.stream.Table"));
        clean("D", "edge/Rules.kt", "import com.ditchoom.socket.quic.core.stream.StreamTable");
        clean("D", "stream/StreamTable.kt", "// import com.ditchoom.socket.quic.core.edge.ConnectionReleased\n"
            + "/* import com.ditchoom.socket.quic.core.conn.Conn */\nval s = \"com.ditchoom.socket.quic.core.loop.Loop.\"");
        clean("D", "conn/Conn.kt", "import com.ditchoom.socket.quic.core.stream.StreamTable\nimport com.ditchoom.socket.quic.core.observe.Counters");
        // S state privacy
        planted("S", "conn/A.kt", "class A { var phase: P = P.Fresh }");
        planted("S", "conn/A.kt", "class A(var n: Int)");
        planted("S", "conn/A.kt", "class A { internal val items = ArrayList<X>() }");
        clean("S", "conn/A.kt", "class A { private var x = 0 }");
        clean("S", "conn/A.kt", "class A {\n    var due: Long = 0L\n        private set\n}");
        clean("S", "conn/A.kt", "class A { fun f() { var i = 0\n i++ } }");
        clean("S", "sched/WorkInbox.kt", "class W { @Link internal var next: W = this }");
        clean("S", "conn/AckHold.kt", "private sealed interface Hold {\n data object Idle : Hold\n class Holding : Hold { var untilNanos = 0L }\n}");
        // W wiring
        planted("W", "conn/A.kt", "class A { lateinit var conn: Conn }");
        planted("W", "conn/A.kt", "typealias Id = Long");
        planted("W", "conn/Turn.kt", "class Turn { private var n = 0 }");
        planted("W", "conn/Compose.kt", "fun build(q: Q): Conn {\n    val life = ConnLife(q)\n    val a = A(life)\n    val b = B(life)\n    return Conn(a, b)\n}");
        clean("W", "conn/Compose.kt", "fun build(q: Q): Conn {\n    val turn = Turn(parts)\n    val table = StreamTable(StreamIo(q))\n"
            + "    val linger = Linger(q)\n    return Conn(ConnLife(q, linger), turn, table)\n}");
        clean("W", "conn/Compose.kt", "fun build(q: Q) = Conn(Turn(q))");
        // E no else
        planted("E", "loop/A.kt", "val r = when (x) { is A -> 1\n else -> 2 }");
        planted("E", "loop/A.kt", "val r = when (p) {\n    Pacing.Unpaced -> a\n    is Pacing.Held -> b\n    else -> c\n}");
        planted("E", "loop/A.kt", "val r = when (x) { is A -> 1 else -> 2 }");
        clean("E", "loop/A.kt", "val r = when { a > b -> 1\n else -> 2 }");
        clean("E", "loop/A.kt", "val r = when (code) { 0 -> a\n QUICHE_ERR_DONE -> b\n else -> c }");
        clean("E", "udp/SocketErrors.kt", "val r = when (e) { is IOException -> 1\n else -> 2 }");
        clean("E", "edge/Failures.kt", "val r = when (t) { is IOException -> 1\n else -> 2 }");
        clean("E", "loop/A.kt", "val r = when (x) { is A -> 1\n is B -> 2 }");
        // O objects
        planted("O", "conn/A.kt", "private sealed interface Pacing { data object Unpaced : Pacing }\nval r = when (p) { Pacing.Unpaced -> a }");
        planted("O", "conn/A.kt", "sealed interface S { class Full : S }");
        clean("O", "conn/A.kt", "enum class Entry { Entered, Busy }\nval r = when (e) { Entry.Entered -> a\n Entry.Busy -> b }");
        clean("O", "conn/A.kt", "sealed interface Pacing { data object Unpaced : Pacing }\nval r = when (p) { is Pacing.Unpaced -> a }");
        clean("O", "conn/A.kt", "sealed interface S { data object Full : S }");
        clean("O", "conn/A.kt", "sealed interface S { class Held(val hold: AckHold) : S }");
        // I seams
        planted("I", "conn/A.kt", "internal interface ConnEvents");
        planted("I", "conn/A.kt", "open class Base");
        planted("I", "conn/A.kt", "sealed interface S { fun f() }");
        CASES.add(new Case("I", true, true, "ready/Poller.kt", "internal interface Poller", "conn/A.kt", "class A"));
        clean("I", "conn/A.kt", "sealed interface Pacing { data object Unpaced : Pacing }");
        clean("I", "ready/Poller.kt", "internal interface Poller");
        CASES.add(new Case("I", false, true, "seams/All.kt", seamsDeclared));
        // N nullable
        planted("N", "conn/A.kt", "fun f(): String? = x");
        clean("N", "conn/A.kt", "fun f() = a ?: b");
        // B Boolean state
        planted("B", "conn/A.kt", "class A { private var done = false }");
        planted("B", "conn/A.kt", "class A { val flag = AtomicBoolean() }");
        planted("B", "conn/A.kt", "class A(val ready: Boolean)");
        clean("B", "conn/A.kt", "class A { val isOpen: Boolean get() = life.load() !== Life.Closed }");
        clean("B", "conn/A.kt", "class A { fun f() { val more = false\n use(more) } }");
        // V value-class boxing
        planted("V", "conn/A.kt", "@JvmInline value class Entrant(val v: Long)\nval l: MutableList<Entrant> = x");
        planted("V", "conn/A.kt", "@JvmInline value class Nanos(val v: Long)\nval f: (Nanos) -> Unit = g");
        clean("V", "conn/A.kt", "@JvmInline value class Nanos(val v: Long)\nfun due(): Nanos = x\nclass Holder(val due: Nanos)\n/** List<Nanos> in KDoc */");
        // F failures
        planted("F", "conn/A.kt", "fun f(e: Exception) = e.cause");
        planted("F", "conn/A.kt", "class X(val cause: Throwable)");
        planted("F", "edge/Failures.kt", "fun f() = QuicCloseException(reason, during)");
        planted("F", "edge/StreamOps.kt", "fun f() { cont.resumeWithException(x) }");
        planted("F", "conn/Ingress.kt", "fun f() { try { g() } catch (e: Throwable) { h() } }");
        planted("F", "conn/A.kt", "fun f(): Nothing = throw Stop()");
        clean("F", "edge/Failures.kt", "fun f() = QuicCloseException(end, during)\nfun g(): Nothing = throw QuicStreamException(end, side)");
        clean("F", "life/ConnEnd.kt", "internal class Thrown(val throwable: Throwable)");
        clean("F", "conn/A.kt", "// e.cause\nval s = \"x.cause\"");
        clean("F", "life/ConnLife.kt", "val peer = q.api.connPeerError(q.handle)");
        planted("F", "life/ConnLife.kt", "val e = IllegalStateException()");
        clean("F", "conn/Turn.kt", "fun f() { try { g() } catch (e: Exception) { h() } }");
        // Q quiche ownership
        planted("Q", "conn/Turn.kt", "fun f() { api.connClose(handle, e) }");
        planted("Q", "conn/Egress.kt", "fun f() { api.connRecv(q, a) }");
        planted("Q", "conn/Turn.kt", "fun f() { quicheApi.accept(config, h) }");
        clean("Q", "loop/Loop.kt", "fun f() { onReady.accept(key) }");
        clean("Q", "life/ConnLife.kt", "fun f() { api.connClose(handle, e) }");
        clean("Q", "conn/Ingress.kt", "fun f() { api.connRecv(q, a) }");
        // T ownership kind
        planted("T", "conn/A.kt", "class A { private var x = 0 }");
        planted("T", "conn/A.kt", "@Published class P { private var x = 0 }");
        planted("T", "conn/A.kt", "class A { private val n = AtomicLong(0) }");
        planted("T", "conn/A.kt", "class A(private var n: Int)");
        clean("T", "conn/A.kt", "@Confined class A { private var x = 0 }");
        clean("T", "conn/A.kt", "@Published\nclass P { private val r = AtomicReference<V>(v) }");
        clean("T", "conn/A.kt", "private sealed interface Hold {\n class Holding : Hold { var untilNanos = 0L }\n}");
        // P phase instances
        String hold = "private sealed interface Hold {\n data object Idle : Hold\n class Holding : Hold { var untilNanos = 0L }\n}\n";
        planted("P", "conn/AckHold.kt", hold + "class A { private var phase: Hold = Hold.Idle\n fun start() { phase = Hold.Holding() } }");
        planted("P", "conn/AckHold.kt", hold + "fun start() { val h = Hold.Holding()\n use(h) }");
        clean("P", "conn/AckHold.kt", hold + "class A { private val holding = Hold.Holding() }");
        clean("P", "conn/Compose.kt", "sealed interface AckCadence { class Held(val hold: AckHold) : AckCadence }\nfun f() = AckCadence.Held(AckHold(r, q))");
        // M magic numbers
        planted("M", "conn/A.kt", "class A { private var due = Long.MAX_VALUE }");
        planted("M", "conn/A.kt", "val Now = Flush(Long.MIN_VALUE)");
        planted("M", "conn/A.kt", "fun f() { if (index == -1) g() }");
        clean("M", "conn/A.kt", "fun f(i: Int) = i - 1\nval s = \"-1\"");
        clean("M", "stream/QuicheCodes.kt", "const val QUICHE_ERR_DONE = -1");
        clean("M", "conn/AckHold.kt", hold);
        // C casts
        planted("C", "conn/A.kt", "val b = (buffer.unwrapFully() as BaseJvmBuffer).byteBuffer");
        planted("C", "conn/A.kt", "val m = buffer.nativeMemoryAccess!!");
        clean("C", "conn/A.kt", "fun f(x: Any) { when (x) { is A -> x.f() }\n if (a != b) g() }");
        clean("C", "udp/NativeBytes.kt", "val b = (buffer.unwrapFully() as BaseJvmBuffer).byteBuffer\nval m = buffer.nativeMemoryAccess!!");
        // L size
        planted("L", "conn/Big.kt", "val a = 1\n".repeat(300) + "val b = 2");
        clean("L", "conn/Big.kt", "val a = 1\n".repeat(299) + "val b = 2");
    }

    /** The seven defaults, exercised under `--rules=legacy` exactly as the tree is checked today. */
    static final Map<String, String> LEGACY_PLANTED = Map.ofEntries(
        Map.entry("lock", "fun f() { synchronized(this) { } }"),
        Map.entry("boolean-property", "class A(val ready: Boolean) { var done: Boolean = false }"),
        Map.entry("nullable", "fun f(): String? = x"),
        Map.entry("message-exception", "fun f() { require(x > 0) { \"x must be positive\" } }"),
        Map.entry("throw-outside-edge", "fun f(): Nothing = throw IllegalStateException()"),
        Map.entry("suspend-outside-edge", "suspend fun f() = Unit"),
        Map.entry("thread-in-initializer", "class A { init { worker.start() } }"),
        Map.entry("byte-array", "fun f(b: ByteArray) = Unit"),
        Map.entry("message-exception-literal", "fun f() = IllegalStateException(\"stream closed\")"),
        Map.entry("own-buffer-factory", "val b = BufferFactory.Default.allocate(1500)"));

    static void selfTest() {
        int failures = 0;
        Map<String, int[]> perRule = new TreeMap<>();
        for (var entry : LEGACY_PLANTED.entrySet()) {
            List<Finding> found = check("planted-" + entry.getKey() + ".kt", "loop", entry.getValue() + "\n");
            int[] counts = perRule.computeIfAbsent(entry.getKey(), k -> new int[3]);
            counts[0]++;
            if (found.stream().anyMatch(f -> f.rule().equals(entry.getKey()))) counts[1]++;
            else {
                System.err.println("SELFTEST: a planted " + entry.getKey() + " violation was not caught: " + found);
                failures++;
            }
        }
        List<String> literalPlants = List.of(
            "fun f() = QuicError.InternalError(\"boom\")",
            "fun f() = StreamFailureException(\n    id,\n    \"stream $id closed\",\n)",
            "class E : Exception(\"x\")",
            "class E(val id: Long) : Exception() { override val message: String = \"stream\" }",
            "val e = X(message = \"sentence\")",
            "fun f(e: Exception) = e.message.orEmpty().contains(x)",
            "fun f(e: Exception) = e?.message");
        for (String plant : literalPlants) {
            List<Finding> found = check("planted-literal.kt", "loop", plant + "\n");
            int[] counts = perRule.computeIfAbsent("message-exception-literal", k -> new int[3]);
            counts[0]++;
            if (found.stream().anyMatch(f -> f.rule().equals("message-exception-literal"))) counts[1]++;
            else {
                System.err.println("SELFTEST: a planted message-exception-literal was not caught: " + plant);
                failures++;
            }
        }
        List<Finding> typedOnly = check("typed.kt", "loop", String.join("\n",
            "fun f() = QuicStreamClosedException(id, QuicStreamSide.Read)",
            "fun g(e: Exception) = Wrapped(e)",
            "fun h(): Exception = lastFailure",
            "fun i() = QuicCloseException(reason, QuicCloseCircumstance.Released)",
            "val text = \"the word Exception( and .message in a string\"",
            "// IllegalStateException(\"in a comment\") e.message",
            ""));
        if (!typedOnly.isEmpty()) {
            System.err.println("SELFTEST: typed exceptions were flagged: " + typedOnly);
            failures++;
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
        cases();
        Map<String, int[]> composition = new LinkedHashMap<>();
        for (Case c : CASES) {
            int[] counts = composition.computeIfAbsent(c.rule(), k -> new int[4]);
            boolean hit = analyzeAll(c.sources(), c.wholeTree()).stream().anyMatch(f -> f.rule().equals(c.rule()));
            if (c.planted()) {
                counts[0]++;
                if (hit) counts[1]++;
                else {
                    System.err.println("SELFTEST: a planted " + c.rule() + " case was not caught: " + c.files()[0] + "\n" + c.files()[1]);
                    failures++;
                }
            } else {
                counts[2]++;
                if (!hit) counts[3]++;
                else {
                    System.err.println("SELFTEST: a clean " + c.rule() + " case was flagged: " + c.files()[0] + "\n" + c.files()[1]
                        + "\n  " + analyzeAll(c.sources(), c.wholeTree()).stream().filter(f -> f.rule().equals(c.rule())).toList());
                    failures++;
                }
            }
        }
        for (String rule : List.of("D", "S", "W", "E", "O", "I", "N", "B", "V", "F", "Q", "T", "P", "M", "C", "L")) {
            int[] counts = composition.get(rule);
            if (counts == null || counts[1] == 0) {
                System.err.println("SELFTEST: rule " + rule + " caught no planted case");
                failures++;
            }
        }
        if (failures > 0) System.exit(1);
        int legacyCaught = perRule.values().stream().mapToInt(c -> c[1]).sum();
        System.out.println("CoreDefaultsCheck selftest: legacy rules, " + legacyCaught + " planted violations caught, clean code passes");
        perRule.forEach((rule, c) -> System.out.println("  rule " + rule + ": planted " + c[1] + "/" + c[0]));
        composition.forEach((rule, c) -> System.out.println("  rule " + rule + ": planted " + c[1] + "/" + c[0] + ", clean " + c[3] + "/" + c[2]));
    }
}

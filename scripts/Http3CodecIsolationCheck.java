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
 * The HTTP/3 codec layer names no QUIC type.
 *
 *     java scripts/Http3CodecIsolationCheck.java <repository root>
 *     java scripts/Http3CodecIsolationCheck.java --selftest
 *
 * The layer of a :socket-http3 file is its directory: everything under `.../com/ditchoom/socket/http3/codec/` is the
 * codec and declares package `com.ditchoom.socket.http3.codec`; nothing else does. A codec file may not mention
 * package `com.ditchoom.socket.quic` (an import or a qualified name), nor any public top-level type declared under
 * that package in :socket-quic or :socket-quic-quiche, nor any non-private top-level type declared in a binding file
 * (a main-source file outside the codec directory): a codec that needs a binding type would drag the transport in
 * with it. A binding file is not checked for QUIC use.
 * The check also fails on a file under the codec directory that declares another package, and on a file anywhere in
 * the module's source sets that declares the codec package from outside the codec directory. Finding no codec file at
 * all is a failure: an empty scan must not look clean.
 */
public class Http3CodecIsolationCheck {
    static final String MODULE = "socket-http3";
    static final List<String> QUIC_MODULES = List.of("socket-quic", "socket-quic-quiche");
    static final String QUIC_DIR = "/com/ditchoom/socket/quic/";
    static final String CODEC_DIR = "/com/ditchoom/socket/http3/codec/";
    static final String CODEC_PACKAGE = "com.ditchoom.socket.http3.codec";

    static final Pattern TOP_LEVEL_TYPE = Pattern.compile("^((?:[a-z]+\\s+)*)(?:class|object|interface|typealias)\\s+`?(\\w+)`?");
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final Pattern QUIC_PACKAGE = Pattern.compile("\\bsocket\\s*\\.\\s*quic\\b");
    static final Pattern PACKAGE_LINE = Pattern.compile("^package\\s+([\\w.]+)", Pattern.MULTILINE);

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--selftest")) {
            selftest();
            return;
        }
        if (args.length != 1) {
            System.err.println("usage: Http3CodecIsolationCheck <repository root> | --selftest");
            System.exit(2);
        }
        Result result = run(Path.of(args[0]));
        System.out.println("Http3CodecIsolationCheck: " + result.codec + " codec files, " + result.binding + " binding files, "
            + result.quicTypes + " QUIC type names");
        for (String line : result.failures) System.err.println(line);
        if (!result.failures.isEmpty()) {
            System.err.println("Http3CodecIsolationCheck: " + result.failures.size() + " failure(s)");
            System.exit(1);
        }
    }

    record Result(int codec, int binding, int quicTypes, List<String> failures) {}

    static boolean inCodecDir(Path file) {
        return file.toString().replace('\\', '/').contains(CODEC_DIR);
    }

    static String declaredPackage(Path file) throws IOException {
        Matcher m = PACKAGE_LINE.matcher(stripped(file));
        return m.find() ? m.group(1) : "";
    }

    static Result run(Path root) throws IOException {
        List<String> failures = new ArrayList<>();

        Map<String, String> quicTypes = new TreeMap<>();
        for (String module : QUIC_MODULES) {
            for (Path file : mainSources(root, module)) {
                if (!file.toString().replace('\\', '/').contains(QUIC_DIR)) continue;
                for (String line : stripped(file).split("\n", -1)) {
                    Matcher m = TOP_LEVEL_TYPE.matcher(line);
                    if (m.find() && !m.group(1).contains("internal") && !m.group(1).contains("private")) quicTypes.putIfAbsent(m.group(2), rel(root, file));
                }
            }
        }
        if (quicTypes.isEmpty()) failures.add("no QUIC type found under " + QUIC_MODULES + "; refusing to pass an empty scan");

        List<Path> main = mainSources(root, MODULE);
        Map<String, String> bindingTypes = new TreeMap<>();
        for (Path file : main) {
            if (inCodecDir(file)) continue;
            for (String line : stripped(file).split("\n", -1)) {
                Matcher m = TOP_LEVEL_TYPE.matcher(line);
                if (m.find() && !m.group(1).contains("private")) bindingTypes.putIfAbsent(m.group(2), rel(root, file));
            }
        }

        // Directory and package agree in every source set of the module, tests included.
        for (Path file : allSources(root, MODULE)) {
            boolean codecDir = inCodecDir(file);
            String pkg = declaredPackage(file);
            if (codecDir && !pkg.equals(CODEC_PACKAGE)) {
                failures.add(rel(root, file) + ": file under the codec directory declares package '" + pkg + "', want " + CODEC_PACKAGE);
            }
            if (!codecDir && pkg.equals(CODEC_PACKAGE)) {
                failures.add(rel(root, file) + ": declares package " + CODEC_PACKAGE + " outside a .../http3/codec/ directory");
            }
        }

        int codec = 0;
        int binding = 0;
        for (Path file : main) {
            if (!inCodecDir(file)) {
                binding++;
                continue;
            }
            codec++;
            String text = stripped(file);
            if (QUIC_PACKAGE.matcher(text).find()) failures.add(rel(root, file) + ": codec file mentions package com.ditchoom.socket.quic");
            Set<String> names = new TreeSet<>();
            Matcher id = IDENTIFIER.matcher(text);
            while (id.find()) names.add(id.group());
            for (String n : names) {
                if (quicTypes.containsKey(n)) {
                    failures.add(rel(root, file) + ": codec file names " + n + ", the QUIC type declared in " + quicTypes.get(n)
                        + " (if this is your own type that merely shares the name, rename it)");
                }
                if (bindingTypes.containsKey(n)) {
                    failures.add(rel(root, file) + ": codec file names " + n + ", declared in binding file " + bindingTypes.get(n)
                        + " (move the type into the codec directory, or move this file out of it)");
                }
            }
        }
        if (codec == 0) failures.add("no codec file scanned; refusing to pass an empty scan");
        return new Result(codec, binding, quicTypes.size(), failures);
    }

    /** Every `.kt` file under `<module>/src`, whatever the source set. */
    static List<Path> allSources(Path root, String module) throws IOException {
        Path src = root.resolve(module).resolve("src");
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(src)) return out;
        try (Stream<Path> walk = Files.walk(src)) {
            walk.filter(p -> p.toString().endsWith(".kt")).sorted().forEach(out::add);
        }
        return out;
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

    static final String H3 = MODULE + "/src/commonMain/kotlin/com/ditchoom/socket/http3/";
    static final String CODEC = H3 + "codec/";
    static final String CP = "package " + CODEC_PACKAGE + "\n";
    static final String BP = "package com.ditchoom.socket.http3\n";

    static void selftest() throws IOException {
        Path root = Files.createTempDirectory("http3-codec-isolation");
        write(root, "socket-quic/src/commonMain/kotlin/com/ditchoom/socket/quic/Ids.kt",
            "package com.ditchoom.socket.quic\nvalue class QuicStreamId(val id: Long)\ninternal class HiddenQuic\n");
        write(root, CODEC + "Frame.kt", CP + "// QuicStreamId in a comment\nval s = \"QuicStreamId\"\nclass Mine { val h = HiddenQuic }\n");
        write(root, H3 + "Conn.kt", BP + "import com.ditchoom.socket.quic.QuicStreamId\nval x = QuicStreamId(1)\nclass Connection\n");
        expect(root, 0, "clean codec file and a binding file using QUIC pass");

        write(root, CODEC + "Frame.kt", CP + "import com.ditchoom.socket.quic.QuicStreamId\n");
        expect(root, 2, "codec import of a QUIC type fails (package and name)");
        write(root, CODEC + "Frame.kt", CP + "val id = QuicStreamId(1)\n");
        expect(root, 1, "codec use of a QUIC type name fails");
        write(root, CODEC + "Frame.kt", CP + "val id = com.ditchoom.socket.quic.Other\n");
        expect(root, 1, "codec qualified QUIC name fails");
        write(root, CODEC + "Frame.kt", CP + "import com.ditchoom.socket.quic.QuicStreamId as Q\nval id = Q(1)\n");
        expect(root, 2, "codec import alias of a QUIC type fails (package and name)");
        write(root, CODEC + "Frame.kt", CP + "val id = com.ditchoom.socket.quic.QuicStreamId(1)\n");
        expect(root, 2, "codec fully qualified QUIC type fails (package and name)");
        write(root, CODEC + "Frame.kt", CP + "val c = Connection()\n");
        expect(root, 1, "codec use of a type declared in a binding file fails");
        write(root, H3 + "Conn.kt", BP + "private class Hidden\ninternal class Connection\n");
        write(root, CODEC + "Frame.kt", CP + "val c = Hidden()\n");
        expect(root, 0, "a private binding type is not a binding type");
        write(root, CODEC + "Frame.kt", CP);

        write(root, CODEC + "Wrong.kt", BP);
        expect(root, 1, "a codec-directory file declaring another package fails");
        write(root, CODEC + "Wrong.kt", "package " + CODEC_PACKAGE + ".sub\n");
        expect(root, 1, "a codec-directory file declaring a sub-package fails");
        Files.delete(root.resolve(CODEC + "Wrong.kt"));

        write(root, H3 + "Stray.kt", CP);
        expect(root, 1, "a main file outside the codec directory declaring the codec package fails");
        Files.delete(root.resolve(H3 + "Stray.kt"));
        write(root, "socket-http3/src/commonTest/kotlin/com/ditchoom/socket/http3/StrayTest.kt", CP);
        expect(root, 1, "a test file outside the codec directory declaring the codec package fails");
        Files.delete(root.resolve("socket-http3/src/commonTest/kotlin/com/ditchoom/socket/http3/StrayTest.kt"));
        write(root, "socket-http3/src/commonTest/kotlin/com/ditchoom/socket/http3/codec/FrameTest.kt", CP);
        expect(root, 0, "a codec test inside the codec directory passes");

        Files.delete(root.resolve(CODEC + "Frame.kt"));
        expect(root, 1, "an empty codec scan fails");
        write(root, CODEC + "Frame.kt", CP);

        write(root, "socket-http3/src/commonTest/kotlin/T.kt", "val id = QuicStreamId(1)\n");
        expect(root, 0, "test source sets are not scanned");
        System.out.println("Http3CodecIsolationCheck selftest: every rule catches its planted violation, clean tree passes");
    }

    static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    static void expect(Path root, int failures, String what) throws IOException {
        List<String> got = run(root).failures;
        if (got.size() != failures) {
            System.err.println("selftest FAILED: " + what + ": expected " + failures + " failure(s), got " + got);
            System.exit(1);
        }
    }
}

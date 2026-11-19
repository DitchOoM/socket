import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Fails when compiled classes reference a java.* or javax.* class, method or field that an Android platform's
 * android.jar does not declare. The run-to-completion QUIC core is shared by the JVM and Android; a JDK 9+ API it
 * calls links on the JVM and throws NoSuchMethodError on an older Android runtime, so the check resolves every
 * constant-pool reference against the minSdk android.jar by exact name and descriptor, the way the runtime links.
 *
 *     java scripts/AndroidApiCheck.java <android.jar> <classes dir> [<classes dir>...]
 *     java scripts/AndroidApiCheck.java --selftest <android.jar>
 *
 * Only the classes under the given directories are scanned, so the argument names the production classes of the
 * core, not their tests. Scanning no class at all is a failure: a scan that matched nothing must not look clean.
 */
public class AndroidApiCheck {
    /** What an android.jar class declares: its supertypes and its public and protected members. */
    record ApiClass(String superName, List<String> interfaces, Set<String> methods, Set<String> fields) {}

    /** A reference a scanned class makes: [owner] declares (or inherits) [name] with [descriptor]. */
    record Reference(String owner, String name, String descriptor, boolean isClassOnly) {
        String text() {
            return isClassOnly ? owner : owner + "." + name + descriptor;
        }
    }

    static final Map<String, ApiClass> API = new HashMap<>();

    /** Linked by D8's desugaring of invokedynamic, so the runtime never resolves them. */
    static final Set<String> DESUGARED = Set.of("java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory");

    /** Classes whose use is chosen by a one-time runtime probe, so the references they hold are never reached. */
    static final Set<String> GUARDED = Set.of("com/ditchoom/socket/quic/core/ready/ConsumerScan");

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--selftest")) {
            System.exit(selftest(Paths.get(args[1])));
        }
        if (args.length < 2) {
            System.err.println("usage: AndroidApiCheck <android.jar> <classes dir>... | --selftest <android.jar>");
            System.exit(2);
        }
        loadApi(Paths.get(args[0]));
        List<Path> roots = new ArrayList<>();
        for (int i = 1; i < args.length; i++) roots.add(Paths.get(args[i]));
        Map<String, Set<String>> missing = new TreeMap<>();
        int scanned = scan(roots, missing);
        if (scanned == 0) {
            System.err.println("AndroidApiCheck: no class found under " + roots);
            System.exit(1);
        }
        if (!missing.isEmpty()) {
            System.err.println("AndroidApiCheck: " + missing.size() + " JDK reference(s) absent from " + args[0] + ":");
            missing.forEach((reference, users) -> System.err.println("  " + reference + "  <-  " + users));
            System.exit(1);
        }
        System.out.println("AndroidApiCheck: " + scanned + " classes, every java.*/javax.* reference is in " + args[0]);
    }

    static int selftest(Path androidJar) throws Exception {
        loadApi(androidJar);
        Path work = Files.createTempDirectory("android-api-selftest");
        Path src = work.resolve("Planted.java");
        Files.writeString(src,
            "public class Planted {\n"
                + "  static Object absent() { return java.nio.file.Path.of(\"/proc/net/udp\"); }\n"
                + "  static Object present() { return java.nio.file.Paths.get(\"/proc/net/udp\"); }\n"
                + "}\n");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler.run(null, null, null, "--release", "21", "-d", work.toString(), src.toString()) != 0) return 1;
        Map<String, Set<String>> missing = new TreeMap<>();
        scan(List.of(work), missing);
        boolean flagsPathOf = missing.keySet().stream().anyMatch(r -> r.startsWith("java/nio/file/Path.of("));
        boolean flagsPathsGet = missing.keySet().stream().anyMatch(r -> r.contains("Paths.get"));
        if (!flagsPathOf || flagsPathsGet) {
            System.err.println("AndroidApiCheck selftest FAILED: Path.of flagged=" + flagsPathOf
                + ", Paths.get flagged=" + flagsPathsGet + ", all=" + missing.keySet());
            return 1;
        }
        System.out.println("AndroidApiCheck selftest ok: planted Path.of is caught, Paths.get is not");
        return 0;
    }

    static boolean isJdk(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/");
    }

    static int scan(List<Path> roots, Map<String, Set<String>> missing) throws IOException {
        int scanned = 0;
        for (Path root : roots) {
            List<Path> classes;
            try (Stream<Path> walk = Files.walk(root)) {
                classes = walk.filter(p -> p.toString().endsWith(".class")).toList();
            }
            for (Path file : classes) {
                scanned++;
                try (InputStream in = Files.newInputStream(file)) {
                    ClassFile cf = ClassFile.read(in);
                    if (GUARDED.contains(cf.name)) continue;
                    for (Reference ref : cf.references) {
                        if (!isJdk(ref.owner) || DESUGARED.contains(ref.owner)) continue;
                        if (!resolves(ref)) {
                            missing.computeIfAbsent(ref.text(), k -> new TreeSet<>()).add(cf.name);
                        }
                    }
                }
            }
        }
        return scanned;
    }

    static boolean resolves(Reference ref) {
        if (!API.containsKey(ref.owner)) return false;
        if (ref.isClassOnly) return true;
        boolean isField = !ref.descriptor.startsWith("(");
        String member = ref.name + ref.descriptor;
        return declares(ref.owner, member, isField, new HashSet<>())
            || (!isField && declares("java/lang/Object", member, false, new HashSet<>()));
    }

    static boolean declares(String type, String member, boolean isField, Set<String> seen) {
        ApiClass c = API.get(type);
        if (c == null || !seen.add(type)) return false;
        if ((isField ? c.fields : c.methods).contains(member)) return true;
        if (c.superName != null && declares(c.superName, member, isField, seen)) return true;
        for (String i : c.interfaces) if (declares(i, member, isField, seen)) return true;
        return false;
    }

    static void loadApi(Path androidJar) throws IOException {
        try (ZipFile zip = new ZipFile(androidJar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || !isJdk(n)) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    ClassFile cf = ClassFile.read(in);
                    API.put(cf.name, new ApiClass(cf.superName, cf.interfaces, cf.methods, cf.fields));
                }
            }
        }
        if (API.isEmpty()) throw new IOException("no java.* class in " + androidJar);
    }

    /** The parts of a class file the check needs: its declared members and every member reference in its pool. */
    static final class ClassFile {
        String name;
        String superName;
        final List<String> interfaces = new ArrayList<>();
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();
        final List<Reference> references = new ArrayList<>();

        static ClassFile read(InputStream raw) throws IOException {
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
            if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            int[] tag = new int[count];
            Object[] value = new Object[count];
            int[] a = new int[count];
            int[] b = new int[count];
            for (int i = 1; i < count; i++) {
                int t = in.readUnsignedByte();
                tag[i] = t;
                switch (t) {
                    case 1 -> value[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> a[i] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        a[i] = in.readUnsignedShort();
                        b[i] = in.readUnsignedShort();
                    }
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("constant pool tag " + t);
                }
            }
            ClassFile cf = new ClassFile();
            in.readUnsignedShort();
            cf.name = className(tag, value, a, in.readUnsignedShort());
            int sup = in.readUnsignedShort();
            cf.superName = sup == 0 ? null : className(tag, value, a, sup);
            int ifaces = in.readUnsignedShort();
            for (int i = 0; i < ifaces; i++) cf.interfaces.add(className(tag, value, a, in.readUnsignedShort()));
            for (int pass = 0; pass < 2; pass++) {
                int members = in.readUnsignedShort();
                for (int m = 0; m < members; m++) {
                    in.readUnsignedShort();
                    String memberName = (String) value[in.readUnsignedShort()];
                    String desc = (String) value[in.readUnsignedShort()];
                    (pass == 0 ? cf.fields : cf.methods).add(memberName + desc);
                    int attrs = in.readUnsignedShort();
                    for (int k = 0; k < attrs; k++) {
                        in.readUnsignedShort();
                        in.skipNBytes(in.readInt());
                    }
                }
            }
            for (int i = 1; i < count; i++) {
                if (tag[i] == 9 || tag[i] == 10 || tag[i] == 11) {
                    String owner = className(tag, value, a, a[i]);
                    while (owner.startsWith("[")) owner = owner.substring(1);
                    if (owner.startsWith("L") && owner.endsWith(";")) owner = owner.substring(1, owner.length() - 1);
                    else if (owner.length() == 1) continue;
                    String nm = (String) value[a[b[i]]];
                    String desc = (String) value[b[b[i]]];
                    cf.references.add(new Reference(owner, nm, desc, false));
                } else if (tag[i] == 7) {
                    String owner = (String) value[a[i]];
                    if (owner.startsWith("[")) continue;
                    cf.references.add(new Reference(owner, "", "", true));
                }
            }
            return cf;
        }

        private static String className(int[] tag, Object[] value, int[] a, int index) {
            return (String) value[a[index]];
        }
    }
}

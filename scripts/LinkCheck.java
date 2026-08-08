import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Resolves every JDK method AND field reference in a multi-release jar's META-INF/versions/** tiers
 * against the JDK running this program.
 *
 * Why this exists (issue #293, from bug #287): those tiers are compiled by jvmToolchain(21) but are
 * selected at runtime by EVERY JDK >= 21. javac refuses to compile against a preview API without
 * --enable-preview and marks the classfile so the JVM won't load it; the Kotlin compiler does neither.
 * So a JDK-21-preview API reference compiles silently, produces a classfile that loads happily on a
 * later JDK, and fails only when the call site executes. Nothing about the artifact looks preview-
 * tainted. That is exactly how Arena.allocateUtf8String (renamed to allocateFrom by JEP 454 when FFM
 * finalised in 22) reached a published artifact and was found by an outside report.
 *
 * jdeps CANNOT substitute: it stops at class granularity, and java.lang.foreign.Arena exists on both
 * JDKs, so a renamed *method* on a present class reads as clean.
 *
 * Run on JDK 24+ (java.lang.classfile is final there; preview in 22/23):
 *     java scripts/LinkCheck.java [--require=<jar-name-prefix>]... <jar>...
 *     java scripts/LinkCheck.java --selftest
 *
 * Exit 1 on any unresolvable reference, or if a --require'd artifact carried no versioned tier at all
 * (a tier that silently stopped being produced must fail, not pass by vacuum).
 */
public class LinkCheck {
    private static int problems = 0;
    private static int checkedRefs = 0;
    private static int scannedClasses = 0;
    private static final Map<String, Integer> tierClassesByJar = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        List<Path> jars = new ArrayList<>();
        Set<String> required = new TreeSet<>();
        boolean selfTest = false;

        for (String a : args) {
            if (a.equals("--selftest")) {
                selfTest = true;
            } else if (a.startsWith("--require=")) {
                required.add(a.substring("--require=".length()));
            } else {
                jars.add(Path.of(a));
            }
        }

        if (selfTest) {
            System.exit(selfTest() ? 0 : 1);
        }

        if (jars.isEmpty()) {
            System.err.println("usage: LinkCheck [--require=<jar-name-prefix>]... <jar>...   |   LinkCheck --selftest");
            System.exit(2);
        }

        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) {
                System.out.println("SKIP (not a file): " + jar);
                continue;
            }
            scanJar(jar);
        }

        // A --require'd artifact with zero tier classes means the multi-release tier stopped being
        // produced. Without this the whole check would pass by scanning nothing — the same
        // "an audit that matched nothing looks like a clean audit" failure mode as #290.
        for (String req : required) {
            int found = 0;
            String matched = null;
            for (Map.Entry<String, Integer> e : tierClassesByJar.entrySet()) {
                if (e.getKey().startsWith(req)) {
                    found += e.getValue();
                    matched = e.getKey();
                }
            }
            if (found == 0) {
                System.out.println("MISSING TIER   no META-INF/versions/** classes found for required artifact '" + req + "'");
                problems++;
            } else {
                System.out.println("tier ok        " + req + " -> " + found + " class(es) in " + matched);
            }
        }

        System.out.println(
                "scanned " + scannedClasses + " versioned-tier class(es), resolved " + checkedRefs
                        + " JDK reference(s) against JDK " + Runtime.version() + " -> " + problems + " problem(s)");
        System.exit(problems == 0 ? 0 : 1);
    }

    private static void scanJar(Path jar) throws Exception {
        String jarName = jar.getFileName().toString();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith("META-INF/versions/") || !n.endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (var in = jf.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                scannedClasses++;
                tierClassesByJar.merge(jarName, 1, Integer::sum);
                checkClass(bytes, jarName + "!/" + n);
            }
        }
    }

    private static void checkClass(byte[] bytes, String where) {
        var model = ClassFile.of().parse(bytes);
        for (PoolEntry pe : model.constantPool()) {
            switch (pe) {
                case MethodRefEntry m ->
                        checkMethod(m.owner().asInternalName(), m.name().stringValue(), m.type().stringValue(), where);
                case InterfaceMethodRefEntry m ->
                        checkMethod(m.owner().asInternalName(), m.name().stringValue(), m.type().stringValue(), where);
                case FieldRefEntry f ->
                        checkField(f.owner().asInternalName(), f.name().stringValue(), f.type().stringValue(), where);
                default -> {
                }
            }
        }
    }

    /** Only the JDK surface is version-skewed; our own and third-party classes travel with the jar. */
    private static boolean isJdkOwner(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/");
    }

    private static void checkMethod(String owner, String name, String desc, String where) {
        if (!isJdkOwner(owner) || name.equals("<init>") || name.equals("<clinit>")) {
            return;
        }
        Class<?> c = load(owner);
        if (c == null) {
            report("MISSING CLASS  " + owner, where);
            return;
        }
        checkedRefs++;

        // Signature-polymorphic methods (MethodHandle.invokeExact/invoke, VarHandle accessors) are
        // declared (Object...)Object and legally carry ANY descriptor at the call site, so descriptor
        // matching is meaningless for them — match on name only. Without this, every invokeExact site
        // in FfmQuicheApi (34 distinct descriptors) reports as missing.
        if (owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle")) {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name)) {
                    return;
                }
            }
            report("MISSING METHOD " + owner + "." + name + " (signature-polymorphic, name-only)", where);
            return;
        }

        MethodType mt;
        try {
            mt = MethodType.fromMethodDescriptorString(desc, LinkCheck.class.getClassLoader());
        } catch (IllegalArgumentException | TypeNotPresentException ex) {
            // A parameter/return type that doesn't exist on this JDK is itself drift.
            report("MISSING TYPE   " + owner + "." + name + desc + "  (" + ex.getMessage() + ")", where);
            return;
        }

        if (findMethod(c, name, mt) == null) {
            report("MISSING METHOD " + owner + "." + name + desc, where);
        }
    }

    /**
     * Field references matter for exactly the same reason method references do, and this repo has a
     * live example: ValueLayout.ADDRESS is typed AddressLayout, which JEP 442 -> 454 renamed from
     * ValueLayout.OfAddress. JVM field resolution requires an EXACT descriptor match, so a preview-era
     * field-type rename yields NoSuchFieldError at the call site — and is completely invisible to a
     * method-reference-only checker. Note AddressLayout appears in no Class constant-pool entry of
     * FfmQuicheApi: it exists only inside this field descriptor.
     */
    private static void checkField(String owner, String name, String desc, String where) {
        if (!isJdkOwner(owner)) {
            return;
        }
        Class<?> c = load(owner);
        if (c == null) {
            report("MISSING CLASS  " + owner, where);
            return;
        }
        checkedRefs++;

        Class<?> expected;
        try {
            // Turn a field descriptor into a Class by parsing it as a no-arg method's parameter.
            expected = MethodType.fromMethodDescriptorString("(" + desc + ")V", LinkCheck.class.getClassLoader())
                    .parameterType(0);
        } catch (IllegalArgumentException | TypeNotPresentException ex) {
            report("MISSING TYPE   " + owner + "." + name + ":" + desc + "  (" + ex.getMessage() + ")", where);
            return;
        }

        Field f = findField(c, name);
        if (f == null) {
            report("MISSING FIELD  " + owner + "." + name + ":" + desc, where);
        } else if (!f.getType().equals(expected)) {
            report("FIELD TYPE     " + owner + "." + name + ":" + desc + "  (JDK has " + f.getType().getName() + ")", where);
        }
    }

    private static Class<?> load(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, LinkCheck.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** Exact name + parameter + return match, walking superclasses and superinterfaces. */
    private static Method findMethod(Class<?> start, String name, MethodType mt) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && m.getReturnType().equals(mt.returnType())
                        && java.util.Arrays.equals(m.getParameterTypes(), mt.parameterArray())) {
                    return m;
                }
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            // Interfaces have no superclass, so without this the walk stops dead on Arena /
            // MemorySegment / SymbolLookup — all interfaces, and all central to the FFM tier.
            queue.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        return null;
    }

    private static Field findField(Class<?> start, String name) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return f;
                }
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            queue.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        return null;
    }

    private static void report(String message, String where) {
        System.out.println(message + "\n    [" + where + "]");
        problems++;
    }

    /**
     * Proves the checker DISCRIMINATES rather than trivially passing — the restore-the-bug leg, made
     * permanent. Encodes the exact #287 signature plus the field-type rename hazard, so a future
     * refactor that neuters the resolver fails here instead of going quietly green forever.
     */
    private static boolean selfTest() {
        record Case(String kind, String owner, String name, String desc, boolean shouldResolve, String why) {
        }
        List<Case> cases = List.of(
                new Case("method", "java/lang/foreign/Arena", "allocateUtf8String",
                        "(Ljava/lang/String;)Ljava/lang/foreign/MemorySegment;", false,
                        "the JDK-21-preview name that JEP 454 renamed — the actual #287 bug"),
                new Case("method", "java/lang/foreign/Arena", "allocateFrom",
                        "(Ljava/lang/String;)Ljava/lang/foreign/MemorySegment;", true,
                        "the finalised JDK 22+ replacement"),
                new Case("method", "java/lang/foreign/Arena", "allocate",
                        "(J)Ljava/lang/foreign/MemorySegment;", true,
                        "inherited from SegmentAllocator — proves the superinterface walk works"),
                new Case("field", "java/lang/foreign/ValueLayout", "ADDRESS",
                        "Ljava/lang/foreign/AddressLayout;", true,
                        "the finalised field type"),
                new Case("field", "java/lang/foreign/ValueLayout", "ADDRESS",
                        "Ljava/lang/foreign/ValueLayout$OfAddress;", false,
                        "the preview-era field type JEP 454 renamed — invisible to a method-only checker"),
                new Case("method", "java/lang/invoke/MethodHandle", "invokeExact",
                        "(Ljava/lang/foreign/MemorySegment;I)Z", true,
                        "signature-polymorphic: an arbitrary descriptor must still resolve"));

        boolean ok = true;
        System.out.println("LinkCheck self-test on JDK " + Runtime.version());
        for (Case c : cases) {
            int before = problems;
            if (c.kind().equals("method")) {
                checkMethod(c.owner(), c.name(), c.desc(), "selftest");
            } else {
                checkField(c.owner(), c.name(), c.desc(), "selftest");
            }
            boolean resolved = (problems == before);
            problems = before;
            boolean pass = resolved == c.shouldResolve();
            ok &= pass;
            System.out.printf("  %-4s %s %s.%s%s%n     expected %s — %s%n",
                    pass ? "PASS" : "FAIL",
                    c.kind(),
                    c.owner(), c.name(), c.kind().equals("field") ? ":" + c.desc() : c.desc(),
                    c.shouldResolve() ? "RESOLVABLE" : "UNRESOLVABLE",
                    c.why());
        }
        System.out.println(ok ? "self-test OK — the checker discriminates" : "self-test FAILED");
        return ok;
    }
}

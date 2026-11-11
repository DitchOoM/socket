#!/usr/bin/env python3
"""Fail if the JNI externals in Kotlin and the C entry points that implement them disagree.

The quiche JNI backend binds by name (no RegisterNatives): the JVM looks up
`Java_<class>_<method>` the first time an `external fun` is called, and nothing checks the C
signature against the Kotlin one. A renamed or retyped function on one side fails at the first call,
or worse reads the wrong register and returns garbage. This check compares both sides at build time:

  - every `external fun` has a `JNI_FN(<name>)` definition, and every `JNI_FN` has an `external fun`;
  - parameter count and each parameter's JNI type match the Kotlin type;
  - the return type matches;
  - the second C parameter is `jclass` for a `@JvmStatic` external and `jobject` for an instance one;
  - the `JNI_FN` macro spells the Kotlin class's fully qualified, JNI-mangled name.

Kotlin -> JNI type table: Long jlong, Int jint, Boolean jboolean, Short jshort, Byte jbyte,
Double jdouble, Float jfloat, Unit void, String jstring, LongArray jlongArray, IntArray jintArray,
ByteArray jbyteArray. Any other type is reported rather than guessed.

  check-jni-signatures.py [--kotlin <JniQuicheApi.kt>] [--c <quiche_jni.c>]
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
DEFAULT_KOTLIN = os.path.join(
    ROOT, "socket-quic-quiche/src/commonJvmMain/kotlin/com/ditchoom/socket/quic/JniQuicheApi.kt"
)
DEFAULT_C = os.path.join(ROOT, "socket-quic-quiche/src/jni/quiche_jni.c")

JNI_TYPES = {
    "Long": "jlong",
    "Int": "jint",
    "Boolean": "jboolean",
    "Short": "jshort",
    "Byte": "jbyte",
    "Double": "jdouble",
    "Float": "jfloat",
    "Unit": "void",
    "String": "jstring",
    "LongArray": "jlongArray",
    "IntArray": "jintArray",
    "ByteArray": "jbyteArray",
}


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def mangle(name):
    """JNI name mangling for the characters that can occur in a package/class/method name."""
    return name.replace("_", "_1").replace(".", "_")


def split_params(text):
    return [p.strip() for p in text.split(",") if p.strip()]


def parse_kotlin(path):
    src = strip_comments(open(path, encoding="utf-8").read())
    package = re.search(r"^\s*package\s+([\w.]+)", src, re.M).group(1)
    cls = re.search(r"^\s*(?:\w+\s+)*object\s+(\w+)", src, re.M).group(1)
    funs = {}
    problems = []
    pattern = re.compile(r"((?:@\w+(?:\([^)]*\))?\s+)*)(?:\w+\s+)*external\s+fun\s+(\w+)\s*\(([^)]*)\)\s*(?::\s*(\w+))?", re.S)
    for m in pattern.finditer(src):
        annotations, name, params, ret = m.group(1), m.group(2), m.group(3), m.group(4) or "Unit"
        kt_params = []
        for p in split_params(params):
            kt = p.split(":", 1)[1].strip().rstrip("?")
            kt_params.append(kt)
        funs[name] = {"static": "@JvmStatic" in annotations, "params": kt_params, "ret": ret}
    # A regex that silently skips a declaration would make the check pass vacuously.
    declared = len(re.findall(r"\bexternal\s+fun\b", src))
    if declared != len(funs):
        problems.append(f"found {declared} `external fun` declarations but parsed {len(funs)}: the parser needs updating")
    return package, cls, funs, problems


def parse_c(path):
    src = strip_comments(open(path, encoding="utf-8").read())
    macro = re.search(r"#define\s+JNI_FN\(\s*\w+\s*\)\s+(\w+?)\s*##", src)
    prefix = macro.group(1) if macro else None
    funs = {}
    pattern = re.compile(r"JNIEXPORT\s+(\w+)\s+JNICALL\s+JNI_FN\(\s*(\w+)\s*\)\s*\(([^)]*)\)", re.S)
    for m in pattern.finditer(src):
        ret, name, params = m.group(1), m.group(2), split_params(m.group(3))
        types = [re.sub(r"\s*\b\w+$", "", p).strip() for p in params]
        funs[name] = {"ret": ret, "params": types}
    return prefix, funs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kotlin", default=DEFAULT_KOTLIN)
    ap.add_argument("--c", default=DEFAULT_C)
    args = ap.parse_args()

    package, cls, kt, problems = parse_kotlin(args.kotlin)
    prefix, c = parse_c(args.c)
    errors = list(problems)

    want_prefix = "Java_" + mangle(package + "." + cls) + "_"
    if prefix != want_prefix:
        errors.append(f"JNI_FN prefix is {prefix!r}, but the Kotlin class {package}.{cls} needs {want_prefix!r}")

    for name in sorted(kt.keys() - c.keys()):
        errors.append(f"{name}: an `external fun` in Kotlin with no JNI_FN({name}) in C (fails with UnsatisfiedLinkError on first call)")
    for name in sorted(c.keys() - kt.keys()):
        errors.append(f"{name}: a JNI_FN in C with no `external fun` in Kotlin (dead, or the Kotlin side was renamed)")

    for name in sorted(kt.keys() & c.keys()):
        k, cc = kt[name], c[name]
        unknown = [t for t in k["params"] + [k["ret"]] if t not in JNI_TYPES]
        if unknown:
            errors.append(f"{name}: Kotlin type(s) {unknown} have no JNI mapping in this check; extend JNI_TYPES")
            continue
        want_ret = JNI_TYPES[k["ret"]]
        if cc["ret"] != want_ret:
            errors.append(f"{name}: return type is {cc['ret']} in C but Kotlin {k['ret']} needs {want_ret}")
        head = cc["params"][:2]
        want_head = ["JNIEnv *", "jclass" if k["static"] else "jobject"]
        if [h.replace("*", " *").replace("  ", " ").strip() for h in head] != want_head:
            errors.append(f"{name}: leading C parameters are {head}, expected {want_head} (@JvmStatic={k['static']})")
        c_params = cc["params"][2:]
        want_params = [JNI_TYPES[t] for t in k["params"]]
        if len(c_params) != len(want_params):
            errors.append(f"{name}: C takes {len(c_params)} parameter(s) after env/class, Kotlin passes {len(want_params)}")
            continue
        for i, (got, want) in enumerate(zip(c_params, want_params)):
            if got != want:
                errors.append(f"{name}: parameter {i + 1} is {got} in C but Kotlin {k['params'][i]} needs {want}")

    if errors:
        print(f"JNI signature check FAILED ({len(errors)} problem(s)):")
        for e in errors:
            print("  " + e)
        return 1
    print(f"ok: {len(kt)} JNI externals in {os.path.basename(args.kotlin)} match {os.path.basename(args.c)} by name, parameter types and return type")
    return 0


if __name__ == "__main__":
    sys.exit(main())

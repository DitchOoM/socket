---
id: shrinking-and-multi-release-jars
title: Shrinking (ProGuard/R8) and multi-release JARs
sidebar_label: Shrinking & multi-release JARs
---

# Shrinking and multi-release JARs

If you shrink your JVM application with ProGuard, read this before you ship. A shrinker can
silently remove behaviour from `network-monitor-jvm`, and no error is produced when it does.

## What the versioned tier contains

`com.ditchoom:network-monitor-jvm` is a **multi-release JAR**. One class name, two bodies:

| location | `defaultJvmNetworkMonitor()` returns |
|---|---|
| jar root | `PollingNetworkMonitor` — samples the interfaces on an interval (5s by default) |
| `META-INF/versions/21/` | `NetlinkNetworkMonitor` (Linux, rtnetlink) / `RouteNetworkMonitor` (macOS, `PF_ROUTE`) — event-driven |

On a JDK 21+ runtime the JVM prefers the versioned copy, so an ordinary build gets the reactive
monitor. **These tiers are not fast and slow versions of one behaviour** — one samples, the other is
pushed to. That distinction is what makes the tier load-bearing.

`com.ditchoom:buffer-jvm` is also multi-release, but its tiers *are* performance variants of
equivalent behaviour, so losing its versioned tier costs throughput and nothing else.

## What ProGuard does to it

ProGuard discards everything under `META-INF/versions/` outright. It does not read those entries as
classes, and it does not copy them as resources. Measured on `network-monitor-jvm` 4.1.2:

```
input   network-monitor-jvm-4.1.2.jar        META-INF/versions/21/ : 9 entries
output  network-monitor-jvm-4.1.2-shrunk.jar META-INF/versions/21/ : 0 entries
```

The manifest still says `Multi-Release: true` afterwards, so nothing downstream notices the overlay
is gone. The shrunk application runs different code than every unshrunk build of the same commit.

**A `-keep` rule cannot repair this.** A keep cannot preserve a class the shrinker never saw. Adding
`-keep class com.ditchoom.socket.** { *; }` was tested: the output still had zero `META-INF/versions/`
entries, and `mapping.txt` contained no `RouteNetworkMonitor`, `NetlinkNetworkMonitor`, or
`FfmRoutingSocketNetworkMonitor` line at all.

## What it costs you

Network changes are observed late — up to the poll interval — and a change that resolves inside the
poll window is never observed at all. A one-second link bounce is invisible.

Anything driven by network transitions degrades with it. Most visibly
`QuicOptions.autoMigrateOnNetworkChange`: a connection cannot migrate off a link it never hears die.

## How you will know

Since v4.10.0 the base tier detects its own wrongful selection. Reaching the base selector on a JDK
21+ runtime means the versioned selector was supposed to shadow it and could not — which is only
possible if it is no longer in the artifact. That check lives in the tier that always survives
shrinking, so it cannot itself be stripped, and it prints to `stderr`:

```
===== com.ditchoom:network-monitor — REACTIVE TIER MISSING =====
This JDK is 21 or newer, but META-INF/versions/21 is not in the artifact, so
NetworkMonitor.default() has fallen back to a polling monitor instead of the
event-driven routing-socket one (rtnetlink on Linux, PF_ROUTE on macOS).
...
```

You can also inspect it programmatically at any time — `NetworkMonitor.default().capability.mechanism`
is `MonitorMechanism.Polled` when the reactive tier is missing.

## Fix: flatten before the shrinker runs

Resolve the multi-release overlay yourself, ahead of ProGuard, so the shrinker sees the same class set
the JVM would have selected — as an ordinary single-tier jar it can actually read.

This is sound whenever **the runtime that will execute the jar is known at build time**. That is the
condition multi-release JARs exist to handle, and packaging tools that bundle a JDK (`jpackage`,
`jlink`) remove it: the runtime is fixed, and it is the one you flatten to. If you ship a library, or
a jar whose consumer JDK you do not control, do not flatten — exclude the artifact from shrinking
instead (see below).

Wire it **only into the release shrink path**. Dev builds, `run`, and tests should keep the untouched
jars; a real JVM resolves them correctly and there is nothing to fix there.

The rules that matter:

- Merge tiers in **ascending** order — root, then `9`, `11`, … up to the target runtime — so a later
  tier overwrites an earlier one. This is the precedence `JarFile`'s runtime-versioned view applies.
- **Drop tiers above** the target runtime.
- **Drop the whole `META-INF/versions/` tree** from the output, and **remove `Multi-Release`** from the
  manifest, so no later tool believes there is still an overlay to resolve.
- **Do not promote a nested `META-INF/MANIFEST.MF`** — it is metadata about the tier, not a replacement
  for the archive's manifest.
- **Do not promote `*.kotlin_module`** — a versioned tier ships its own under a *different* file name
  (`…_java21.kotlin_module`), so promoting it overwrites nothing and instead leaves two module files
  describing the same package's top-level facades. The format is a per-package protobuf, so there is
  no correct merge.

A Gradle task implementing exactly that:

```kotlin
@CacheableTask
abstract class FlattenMultiReleaseJars : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputJars: ConfigurableFileCollection

    /** The JDK feature version that will actually run the output. */
    @get:Input abstract val runtimeVersion: Property<Int>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun flatten() {
        val out = outputDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        inputJars.files.filter { it.isFile && it.extension == "jar" }.forEach { jar ->
            ZipFile(jar).use { zip ->
                val versioned = Regex("""^META-INF/versions/(\d+)/(.+)$""")
                // Ascending tier order: root (0) first, so higher tiers overwrite it.
                val winners = LinkedHashMap<String, ZipEntry>()
                zip.entries().toList()
                    .filterNot { it.isDirectory }
                    .map { entry ->
                        val m = versioned.matchEntire(entry.name)
                        val tier = m?.groupValues?.get(1)?.toInt() ?: 0
                        val path = m?.groupValues?.get(2) ?: entry.name
                        Triple(tier, path, entry)
                    }
                    .filter { (tier, path, _) ->
                        tier <= runtimeVersion.get() &&
                            !(tier > 0 && (path == "META-INF/MANIFEST.MF" || path.endsWith(".kotlin_module")))
                    }
                    .sortedBy { it.first }
                    .forEach { (_, path, entry) -> winners[path] = entry }

                ZipOutputStream(out.resolve(jar.name).outputStream().buffered()).use { zos ->
                    winners.forEach { (path, entry) ->
                        if (path == "META-INF/MANIFEST.MF") {
                            val mf = zip.getInputStream(entry).use { Manifest(it) }
                            mf.mainAttributes.remove(Attributes.Name("Multi-Release"))
                            zos.putNextEntry(ZipEntry(path))
                            mf.write(zos)
                        } else {
                            zos.putNextEntry(ZipEntry(path))
                            zip.getInputStream(entry).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}
```

Point your shrink task's input at `outputDir` instead of the original configuration, and have the
task fail if it stops matching any input jar — otherwise a future dependency-graph change quietly
turns the whole thing into a no-op.

## Alternative: exclude the artifact from shrinking

If flattening does not apply — you ship a library, or the consumer JDK is not yours to pick — keep
`network-monitor-jvm` out of the shrinker's inputs entirely and put it on the runtime classpath
untouched.

## Verifying

The check is one command, and it is worth putting in CI:

```bash
unzip -l build/shrunk/network-monitor-jvm-*.jar | grep -c 'META-INF/versions/21/'
```

Zero on a JDK 21+ target means the tier is gone. Confirm the positive case too — the count should
match the input jar's — because a rule that silently stops matching reads exactly like a fix.

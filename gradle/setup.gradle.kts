@file:Suppress("unused")

import groovy.util.Node
import groovy.xml.XmlParser
import java.net.URI

class Version(
    val major: UInt,
    val minor: UInt,
    val patch: UInt,
    val snapshot: Boolean,
) {
    fun incrementMajor() = Version(major + 1u, 0u, 0u, snapshot)

    fun incrementMinor() = Version(major, minor + 1u, 0u, snapshot)

    fun incrementPatch() = Version(major, minor, patch + 1u, snapshot)

    fun snapshot() = Version(major, minor, patch, true)

    fun isVersionZero() = major == 0u && minor == 0u && patch == 0u

    override fun toString(): String =
        if (snapshot) {
            "$major.$minor.$patch-SNAPSHOT"
        } else {
            "$major.$minor.$patch"
        }
}

// A prerelease (5.0.0-alpha01) is not a release: the next release is counted from the last release.
private val release = Regex("""(\d+)\.(\d+)\.(\d+)""")

private var latestVersion = Version(0u, 0u, 0u, true)

@Suppress("UNCHECKED_CAST")
fun getLatestVersion(): Version {
    val cached = latestVersion
    if (!cached.isVersionZero()) {
        return cached
    }
    val xml = URI("https://repo1.maven.org/maven2/com/ditchoom/${rootProject.name}/maven-metadata.xml").toURL().readText()
    val versioning = XmlParser().parseText(xml)["versioning"] as List<Node>
    val published = (versioning.first()["versions"] as List<Node>).first()["version"] as List<Node>
    val result =
        published
            .mapNotNull { release.matchEntire(it.text()) }
            .map { it.destructured.let { (major, minor, patch) -> Version(major.toUInt(), minor.toUInt(), patch.toUInt(), false) } }
            .maxWith(compareBy({ it.major }, { it.minor }, { it.patch }))
    latestVersion = result
    return result
}

fun getNextVersion(snapshot: Boolean): Version {
    var v = getLatestVersion()
    if (snapshot) {
        v = v.snapshot()
    }
    if (project.hasProperty("incrementMajor") && project.property("incrementMajor") == "true") {
        return v.incrementMajor()
    } else if (project.hasProperty("incrementMinor") && project.property("incrementMinor") == "true") {
        return v.incrementMinor()
    }
    return v.incrementPatch()
}

project.extra.set("getNextVersion", ::getNextVersion)

package consumer.smoke

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.default
import com.ditchoom.socket.enumerateNetworkInterfaces
import com.ditchoom.socket.networkId
import com.ditchoom.socket.permits
import com.ditchoom.socket.transport.NetworkId
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Behavioural smoke for network awareness against the PUBLISHED artifacts — the runtime half of
 * [Smoke.networkAwareness], whose compile/link halves the K/N gates cover.
 *
 * Since issue #269 all of this ships in `com.ditchoom:network-monitor`, re-exported over
 * `com.ditchoom:socket`'s `api` edge; this project declares only `socket` (see its build file), so
 * RESOLVING these symbols at runtime is itself the assertion that the published POM carries the
 * transitive dependency at the right scope.
 *
 * Running it adds what neither resolution nor a link gate can see on the JVM: `network-monitor-jvm` is
 * a **multi-release JAR** whose reactive FFM routing-socket monitors sit under `META-INF/versions/21`
 * with a Java-8 polling base. Which one `default()` selects depends on the assembled jar being shaped
 * correctly — the same packaging class of bug the loopback smoke catches for the quiche natives.
 *
 * Assertions are host-independent on purpose: a CI runner's interface set, rung and identity are
 * whatever they are. What must hold everywhere is that the calls work, loopback exists, and the monitor
 * does not publish a state its own declared capability forbids.
 */
class NetworkAwarenessSmokeTest {
    @Test
    fun publishedNetworkMonitorResolvesAndWorksThroughSocketsApiEdge() {
        val interfaces = enumerateNetworkInterfaces()
        assertTrue(
            interfaces.any { it.isLoopback },
            "every host has a loopback interface; enumerate returned ${interfaces.map { it.name }}",
        )

        val monitor = NetworkMonitor.default()
        try {
            val state = monitor.state.value
            assertTrue(
                monitor.capability.resolution.permits(state),
                "${monitor::class.java.name} declares ${monitor.capability.resolution} but published $state",
            )
            // The sealed identity contract survives publication — never a bare string or null.
            val id = state.networkId
            assertTrue(
                id is NetworkId.Link || id is NetworkId.KindOnly || id == NetworkId.Unidentified,
                "networkId must be a sealed NetworkId, was $id",
            )
            println("consumer-smoke: network awareness OK — ${Smoke.networkAwareness()}")
        } finally {
            monitor.close()
        }
    }
}

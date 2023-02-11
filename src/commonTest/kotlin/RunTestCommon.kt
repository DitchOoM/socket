import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlin.test.assertFailsWith

expect fun <T> block(body: suspend CoroutineScope.() -> T)

fun <T> blockingTest(body: suspend CoroutineScope.() -> T) = block {
    if (getNetworkCapabilities() == NetworkCapabilities.FULL_SOCKET_ACCESS) {
        body()
    } else {
        assertFailsWith<UnsupportedOperationException> {
            body()
        }
    }
}

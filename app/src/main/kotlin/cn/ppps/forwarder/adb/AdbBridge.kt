package cn.ppps.forwarder.adb

import android.content.Context
import android.util.Log
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException

private const val TAG = "AdbBridge"

/**
 * The in-app ADB bridge (Shizuku-free).
 *
 * Connects to the local adbd listener (127.0.0.1:<port>, default 5555),
 * authenticates with our own on-device RSA key, and runs shell commands.
 * This is the technique that lets the fleet-agent start a shell-privileged
 * process (uid=2000) without the Shizuku app installed.
 *
 * Derived from Shizuku (github.com/thedjchi/Shizuku, Apache-2.0).
 * Async style follows the fork: RxJava2.
 */
object AdbBridge {

    /** The default adbd TCP port. */
    const val DEFAULT_PORT = 5555

    /** Result of one shell command. */
    data class ShellResult(val exitOk: Boolean, val output: String, val error: String?)

    private fun keyFor(context: Context): AdbKey =
        AdbKey(
            PreferenceAdbKeyStore(context.getSharedPreferences("fleet_adb", Context.MODE_PRIVATE)),
            "fleet-agent"
        )

    /**
     * Run one shell command through the bridge, capturing stdout.
     * Emits [ShellResult]; emits [AdbBridgeException] when the bridge cannot connect.
     */
    fun shell(context: Context, command: String, port: Int = DEFAULT_PORT): Single<ShellResult> =
        Single.create { emitter ->
            val buffer = ByteArrayOutputStream()
            try {
                AdbClient("127.0.0.1", port, keyFor(context)).use { client ->
                    connectWithRetry(client)
                    client.command("shell:$command") { data -> buffer.write(data) }
                }
                emitter.onSuccess(
                    ShellResult(
                        exitOk = true,
                        output = buffer.toString(Charsets.UTF_8.name()).trim(),
                        error = null
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "shell failed: $command", e)
                emitter.onError(AdbBridgeException("bridge shell failed: ${e.message}", e))
            }
        }.subscribeOn(Schedulers.io())

    /**
     * True when the bridge can reach adbd and complete the handshake.
     */
    fun isAvailable(context: Context, port: Int = DEFAULT_PORT): Single<Boolean> =
        Single.create { emitter ->
            try {
                AdbClient("127.0.0.1", port, keyFor(context)).use { client ->
                    client.connect()
                }
                emitter.onSuccess(true)
            } catch (e: Exception) {
                Log.w(TAG, "bridge unavailable on port $port", e)
                emitter.onSuccess(false)
            }
        }.subscribeOn(Schedulers.io())

    /** Fire-and-forget a shell command; completes when the stream closes. */
    fun exec(context: Context, command: String, port: Int = DEFAULT_PORT): Completable =
        shell(context, command, port).ignoreElement()

    private fun connectWithRetry(client: AdbClient) {
        var delayTime = 0L
        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            try {
                if (delayTime > 0) Thread.sleep(delayTime)
                client.connect()
                break
            } catch (e: Exception) {
                if (
                    attempt == maxAttempts ||
                    e is SocketTimeoutException
                ) throw e
                delayTime += 1000
            }
        }
    }
}

class AdbBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

package herdr.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What became of a message typed to a terminal. */
enum class SendOutcome(val notice: String?) {
    /** Nothing was written: no terminal attached, not connected. Safe to try again. */
    Rejected("Not sent: the terminal is not connected. Your message is still in the box."),

    /** Written and flushed to the SSH link; the core has not confirmed it. */
    Accepted(null),

    /** Written, but no word it arrived (timeout, or the link dropped). It may have gone through. */
    Unknown("It may have been sent. Check the terminal before sending again."),
}

/** The draft box after a send: only a delivered message that is still the draft goes away. */
fun draftAfter(current: String, sent: String, outcome: SendOutcome): String =
    if (outcome == SendOutcome.Accepted && current == sent) "" else current

/** What a failed core call says about whether it may have taken effect. */
fun outcomeOfFailure(e: Throwable): SendOutcome =
    if (e is CoreLostException && !e.afterWrite) SendOutcome.Rejected else SendOutcome.Unknown

/**
 * Writes [bytes] to a terminal channel and waits (up to [timeoutMs]) to learn
 * the outcome. Blocks: never call it on the UI thread. Never retries.
 */
fun sendInput(core: CoreClient?, channel: Int?, bytes: ByteArray, timeoutMs: Long = 5_000): SendOutcome {
    if (core == null || channel == null || core.closed) return SendOutcome.Rejected
    return awaitOutcome(core.inputAwait(channel, bytes), timeoutMs)
}

fun awaitOutcome(pending: CompletableFuture<SendOutcome>, timeoutMs: Long): SendOutcome = try {
    pending.get(timeoutMs, TimeUnit.MILLISECONDS)
} catch (_: TimeoutException) {
    SendOutcome.Unknown
} catch (_: Exception) {
    SendOutcome.Unknown
}

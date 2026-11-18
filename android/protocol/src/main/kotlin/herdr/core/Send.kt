package herdr.core

/** What became of a message typed to a terminal. */
enum class SendOutcome(val notice: String?) {
    /** Nothing was written, or the core refused it: not connected, no such terminal. Safe to try again. */
    Rejected("Not sent: the Mac did not take it (not connected, or the terminal is gone). Your message is still in the box."),

    /** The core replied: it pasted the message and pressed Enter. */
    Accepted(null),

    /** Written, but no word back (timeout, or the link dropped). It may have gone through. */
    Unknown("It may have been sent. Check the terminal before sending again."),
}

/** The draft box after a send: only a delivered message that is still the draft goes away. */
fun draftAfter(current: String, sent: String, outcome: SendOutcome): String =
    if (outcome == SendOutcome.Accepted && current == sent) "" else current

/** What a failed core call says about whether it may have taken effect. */
fun outcomeOfFailure(e: Throwable): SendOutcome =
    if (e is CoreLostException && !e.afterWrite) SendOutcome.Rejected else SendOutcome.Unknown

/**
 * Sends [text] to [terminal] through the core and waits (up to [timeoutMs]) for its reply: the core pastes
 * it and presses Enter (`terminal.send`; a bare Enter with `terminal.keys` when [text] is empty). The core
 * waits for a quiet moment first, and `force` lets it type into an agent that is blocked on a question.
 * A reply is [SendOutcome.Accepted]; a refusal, or a failure before anything was written, is
 * [SendOutcome.Rejected]; a timeout or lost link after writing is [SendOutcome.Unknown].
 * Blocks: never call it on the UI thread. Never retries.
 */
fun sendMessage(core: CoreClient?, terminal: String, text: String, timeoutMs: Long = 15_000): SendOutcome {
    if (core == null || core.closed) return SendOutcome.Rejected
    return try {
        if (text.isEmpty()) core.call("terminal.keys", obj("terminal" to terminal, "keys" to listOf("enter")), timeoutMs)
        else core.call("terminal.send", obj("terminal" to terminal, "text" to text, "force" to true), timeoutMs)
        SendOutcome.Accepted
    } catch (_: RpcException) {
        SendOutcome.Rejected
    } catch (e: Exception) {
        outcomeOfFailure(e)
    }
}

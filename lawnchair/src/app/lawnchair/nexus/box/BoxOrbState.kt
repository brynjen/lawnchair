package app.lawnchair.nexus.box

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** The neural-orb renderer's lane count: a slot outside `0 until ORB_LANES` cannot be drawn. */
const val ORB_LANES = 8

/**
 * What the box is doing, reduced from a [SubstrateFrame] the way the phone's `SubstrateState` is
 * (`substrate_repository_impl.dart`), but keeping every slot, because the launcher's orb draws all
 * of them.
 *
 * [UNKNOWN] is "we have not looked": before the first frame and after any drop. It is not a resting
 * box, so an orb drawing it must not dim.
 */
data class BoxOrbState(
    val slots: List<SubstrateSlot>,
    /** Slots doing real work: everything but idle and offline (an unknown mode counts). */
    val busy: Int,
    /** How many slots the box has; zero until the first frame. */
    val capacity: Int,
    /** Activities running that could not get a slot; non-zero means [busy] is an under-count. */
    val dropped: Int,
) {
    /** Whether a frame has ever arrived on this subscription. */
    val isKnown: Boolean get() = capacity > 0

    /** The slot a one-orb view would draw: the highest on the ladder, the earliest among equals. */
    val salient: SubstrateSlot? get() = slots.maxByOrNull { it.mode.priority }

    val mode: SubstrateMode get() = salient?.mode ?: SubstrateMode.Idle

    /** The most salient thing is a refused token: the box is up but shut. Not [offline]. */
    val unauthorized: Boolean get() = isKnown && mode == SubstrateMode.Unauthorized

    /** The most salient thing is the box being down (a resting slot reporting `offline`). */
    val offline: Boolean get() = isKnown && mode == SubstrateMode.Offline

    /** Something is wrong rather than happening: a failed activity or a shut door. */
    val trouble: Boolean get() = mode == SubstrateMode.Error || mode == SubstrateMode.Unauthorized

    /** The slots the orb can draw: inside its lanes, one per lane. */
    val drawable: List<SubstrateSlot> get() = slots.filter { it.slot in 0 until ORB_LANES }.distinctBy { it.slot }

    companion object {
        val UNKNOWN = BoxOrbState(emptyList(), busy = 0, capacity = 0, dropped = 0)

        fun from(frame: SubstrateFrame) = BoxOrbState(
            slots = frame.slots,
            busy = frame.slots.count { it.mode != SubstrateMode.Idle && it.mode != SubstrateMode.Offline },
            capacity = frame.capacity,
            dropped = frame.dropped,
        )
    }
}

/**
 * The box's [BoxOrbState] for as long as it is collected: one state per frame, and [BoxOrbState.UNKNOWN]
 * whenever the stream is lost, so a dead socket never keeps claiming what the box was doing. Every
 * frame is a whole snapshot, so each connection is correct from its first frame.
 *
 * A drop (error, clean close, or the server ending the stream after refusing the session key, which
 * is indistinguishable here) is retried after [minRetryMs], doubling to [maxRetryMs]; a frame resets
 * it. Cancelling the collection stops it, and with it the socket. [wait] is the pause between
 * attempts (a real delay; a test passes one that only records).
 */
fun boxOrbStates(
    minRetryMs: Long = 2_000,
    maxRetryMs: Long = 30_000,
    wait: suspend (Long) -> Unit = { delay(it) },
    frames: () -> Flow<SubstrateFrame>,
): Flow<BoxOrbState> = flow {
    var retryMs = minRetryMs
    while (true) {
        frames()
            .map {
                retryMs = minRetryMs
                BoxOrbState.from(it)
            }
            .catch { }
            .collect { emit(it) }
        emit(BoxOrbState.UNKNOWN)
        wait(retryMs)
        retryMs = (retryMs * 2).coerceAtMost(maxRetryMs)
    }
}

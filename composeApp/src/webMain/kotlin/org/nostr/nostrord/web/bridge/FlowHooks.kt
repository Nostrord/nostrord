package org.nostr.nostrord.web.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import react.useEffect
import react.useRef
import react.useState

/**
 * Bridge between the shared Kotlin coroutine world (StateFlow / suspend) and React.
 *
 * The whole point of staying on kotlin-react (vs a separate TS app) is that the web
 * UI consumes `AppModule` / managers / their StateFlows directly — no @JsExport, no
 * serialization boundary. These hooks are the only adapter needed.
 *
 * kotlin-react's `useEffect` takes a `suspend CoroutineScope.() -> Unit` whose scope is
 * cancelled automatically on unmount or when a dependency changes, so collection is
 * torn down for us — no manual MainScope/cancel bookkeeping.
 */

/**
 * Collect a [StateFlow] into React state. Equivalent of Compose's `collectAsState()`.
 * Seeds from `flow.value` and re-renders on every emission.
 *
 * A caller can hand over a DIFFERENT flow (a keyed `useViewModel` rebuilt for the newly opened
 * peer or group). `useState`'s initializer only runs on mount, so the state would keep the old
 * flow's value until the effect below re-subscribes, and effects run after paint: the new subject
 * would paint once wearing the previous one's data (the DM header showing the last peer's avatar
 * and name). Re-seeding during render discards that frame before it commits.
 */
fun <T> useStateFlow(flow: StateFlow<T>): T {
    val (state, setState) = useState { flow.value }
    val lastFlow = useRef<StateFlow<T>>(null)
    var current = state
    when {
        lastFlow.current == null -> lastFlow.current = flow
        lastFlow.current !== flow -> {
            lastFlow.current = flow
            current = flow.value
            setState(current)
        }
    }
    useEffect(flow) {
        flow.collect { setState(it) }
    }
    return current
}

/**
 * Collect a cold/hot [Flow] with no inherent current value, using [initial] until the
 * first emission.
 *
 * A swapped flow falls back to [initial] for the same reason [useStateFlow] re-seeds: carrying
 * the previous flow's last value into a new subject shows one frame of the wrong thing.
 */
fun <T> useFlow(flow: Flow<T>, initial: T): T {
    val (state, setState) = useState { initial }
    val lastFlow = useRef<Flow<T>>(null)
    var current = state
    when {
        lastFlow.current == null -> lastFlow.current = flow
        lastFlow.current !== flow -> {
            lastFlow.current = flow
            current = initial
            setState(current)
        }
    }
    useEffect(flow) {
        flow.collect { setState(it) }
    }
    return current
}

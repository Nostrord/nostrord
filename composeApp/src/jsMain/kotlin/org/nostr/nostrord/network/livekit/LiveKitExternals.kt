@file:JsModule("livekit-client")
@file:JsNonModule

package org.nostr.nostrord.network.livekit

import kotlin.js.Promise

/**
 * Externals for the parts of the `livekit-client` SDK the AV space uses. Deliberately
 * minimal: the room lifecycle, the two capture toggles and track attach/detach.
 */
external class Room {
    val localParticipant: LocalParticipant

    /** JS `Map<identity, RemoteParticipant>` — iterated through [kotlin.js.Json] dynamics. */
    val remoteParticipants: dynamic

    fun connect(url: String, token: String): Promise<Unit>

    fun disconnect(stopTracks: Boolean = definedExternally): Promise<Unit>

    fun on(event: String, callback: (dynamic) -> Unit): Room

    fun removeAllListeners(): Room
}

open external class Participant {
    /** LiveKit identity. NIP-29 relays prefix it with the 64-char hex pubkey. */
    val identity: String
    val isSpeaking: Boolean
    val isCameraEnabled: Boolean
    val isMicrophoneEnabled: Boolean
    val isLocal: Boolean

    /** JS `Map<trackSid, TrackPublication>`, video publications only. */
    val videoTrackPublications: dynamic
}

external class LocalParticipant : Participant {
    fun setMicrophoneEnabled(enabled: Boolean): Promise<dynamic>

    fun setCameraEnabled(enabled: Boolean): Promise<dynamic>
}

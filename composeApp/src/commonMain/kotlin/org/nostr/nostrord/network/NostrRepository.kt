package org.nostr.nostrord.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.urlDecode

object NostrRepository {
    private var client: NostrGroupClient? = null
    private var metadataClient: NostrGroupClient? = null
    private var isConnecting = false

    private var keyPair: KeyPair? = null
    
    // NIP-46 Bunker support
    private var nip46Client: Nip46Client? = null
    private var isBunkerLogin = false
    private var bunkerUserPubkey: String? = null
    
    private val metadataRelays = listOf(
        "wss://relay.damus.io",
    )
    private var currentMetadataRelayIndex = 0
    
    private val _currentRelayUrl = MutableStateFlow("wss://groups.fiatjaf.com")
    val currentRelayUrl: StateFlow<String> = _currentRelayUrl.asStateFlow()
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val groups: StateFlow<List<GroupMetadata>> = _groups.asStateFlow()
    
    private val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages.asStateFlow()
    
    private val _joinedGroups = MutableStateFlow<Set<String>>(emptySet())
    val joinedGroups: StateFlow<Set<String>> = _joinedGroups.asStateFlow()
    
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    private val _isBunkerConnected = MutableStateFlow(false)
    val isBunkerConnected: StateFlow<Boolean> = _isBunkerConnected.asStateFlow()

    private val _userMetadata = MutableStateFlow<Map<String, UserMetadata>>(emptyMap())
    val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata.asStateFlow()

    private val _cachedEvents = MutableStateFlow<Map<String, CachedEvent>>(emptyMap())
    val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents.asStateFlow()

    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    private var kind10009SubId: String? = null
    private var kind10009Received = false
    private var eoseReceived = false
    
    private val allRelayGroups = mutableMapOf<String, MutableSet<String>>()
    
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    suspend fun initialize() {
        val savedRelayUrl = SecureStorage.getCurrentRelayUrl()
        if (savedRelayUrl != null) {
            _currentRelayUrl.value = savedRelayUrl
            println("✅ Loaded saved relay URL: $savedRelayUrl")
        }
        
        // Check for bunker login first
        val savedBunkerUrl: String? = SecureStorage.getBunkerUrl()
        val savedUserPubkey: String? = SecureStorage.getBunkerUserPubkey()
        
        if (savedBunkerUrl != null && savedUserPubkey != null) {
            try {
                println("🔐 Restoring bunker session...")
                val bunkerInfo = parseBunkerUrl(savedBunkerUrl)
                val savedClientPrivateKey = SecureStorage.getBunkerClientPrivateKey()
                
                // Use saved client private key to maintain session identity
                val newNip46Client = if (savedClientPrivateKey != null) {
                    println("   Using saved client keypair for session continuity")
                    Nip46Client(savedClientPrivateKey)
                } else {
                    println("   No saved client key, generating new (may need re-authorization)")
                    Nip46Client()
                }
                
                // Set the user pubkey immediately from saved value
                bunkerUserPubkey = savedUserPubkey
                isBunkerLogin = true
                _joinedGroups.value = SecureStorage.getJoinedGroupsForRelay(_currentRelayUrl.value)
                
                println("✅ Loaded bunker user pubkey: ${savedUserPubkey.take(16)}...")
                
                // Connect to bunker - wait for it to complete
                try {
                    try {
                        newNip46Client.connect(
                            remoteSignerPubkey = bunkerInfo.pubkey,
                            relays = bunkerInfo.relays,
                            secret = bunkerInfo.secret
                        )
                    } catch (e: Exception) {
                        // "already connected" means the signer remembers us - this is success!
                        if (e.message?.contains("already connected", ignoreCase = true) == true) {
                            println("✅ Signer reports already connected - reusing session")
                        } else {
                            throw e
                        }
                    }
                    
                    nip46Client = newNip46Client
                    _isBunkerConnected.value = true
                    
                    // Save client key if it was newly generated
                    if (savedClientPrivateKey == null) {
                        SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
                    }
                    
                    // Verify the pubkey matches
                    try {
                        val actualUserPubkey = newNip46Client.getPublicKey()
                        if (actualUserPubkey != savedUserPubkey) {
                            println("⚠️ Bunker returned different pubkey, updating...")
                            bunkerUserPubkey = actualUserPubkey
                            SecureStorage.saveBunkerUserPubkey(actualUserPubkey)
                        }
                    } catch (e: Exception) {
                        println("⚠️ Could not verify pubkey, using saved: ${savedUserPubkey.take(16)}...")
                    }
                    
                    println("✅ Restored bunker connection for ${bunkerUserPubkey?.take(16)}...")
                } catch (e: Exception) {
                    println("⚠️ Initial bunker reconnection failed: ${e.message}")
                    println("   Will retry when signing is needed")
                    // Don't set _isBunkerConnected to true, signEvent will try to reconnect
                }
                
                // Set logged in after bunker setup attempt
                _isLoggedIn.value = true

                // Connect to metadata relay first to load user profile faster
                connectToMetadataRelay()
                connect()
                _isInitialized.value = true
                return
            } catch (e: Exception) {
                println("❌ Failed to restore bunker session: ${e.message}")
                SecureStorage.clearBunkerUrl()
                SecureStorage.clearBunkerUserPubkey()
                SecureStorage.clearBunkerClientPrivateKey()
                isBunkerLogin = false
                bunkerUserPubkey = null
            }
        }
        
        // Fall back to private key login
        val savedPrivateKey = SecureStorage.getPrivateKey()
        if (savedPrivateKey != null) {
            try {
                keyPair = KeyPair.fromPrivateKeyHex(savedPrivateKey)
                _isLoggedIn.value = true
                _joinedGroups.value = SecureStorage.getJoinedGroupsForRelay(_currentRelayUrl.value)
                println("✅ Loaded saved credentials and ${_joinedGroups.value.size} joined groups for relay")
                // Connect to metadata relay first to load user profile faster
                connectToMetadataRelay()
                connect()
            } catch (e: Exception) {
                println("❌ Failed to load saved credentials: ${e.message}")
                SecureStorage.clearPrivateKey()
            }
        }

        // Mark initialization as complete (whether logged in or not)
        _isInitialized.value = true
    }

   fun clearAuthUrl() {
    _authUrl.value = null
}

suspend fun loginWithBunker(bunkerUrl: String): String {
    val bunkerInfo = parseBunkerUrl(bunkerUrl)
    
    // Check if we have an existing client key (from previous session with same signer)
    val existingClientKey = SecureStorage.getBunkerClientPrivateKey()
    val newNip46Client = if (existingClientKey != null) {
        println("🔑 Reusing existing client keypair for bunker connection")
        Nip46Client(existingClientKey)
    } else {
        println("🔑 Generating new client keypair for bunker connection")
        Nip46Client(null)
    }
    
    // Set up auth URL callback
    newNip46Client.onAuthUrl = { url ->
        println("🔐 Auth URL received: $url")
        _authUrl.value = url
    }
    
    try {
        newNip46Client.connect(
            remoteSignerPubkey = bunkerInfo.pubkey,
            relays = bunkerInfo.relays,
            secret = bunkerInfo.secret
        )
    } catch (e: Exception) {
        // "already connected" means the signer remembers us - this is success!
        if (e.message?.contains("already connected", ignoreCase = true) == true) {
            println("✅ Signer reports already connected - reusing session")
        } else {
            throw e
        }
    }
    
    val userPubkey = newNip46Client.getPublicKey()
    
    nip46Client = newNip46Client
    bunkerUserPubkey = userPubkey
    isBunkerLogin = true
    keyPair = null
    
    // Save bunker URL, user pubkey, AND client private key for session persistence
    SecureStorage.saveBunkerUrl(bunkerUrl)
    SecureStorage.saveBunkerUserPubkey(userPubkey)
    SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
    SecureStorage.clearPrivateKey()
    
    _isLoggedIn.value = true
    _isBunkerConnected.value = true
    _authUrl.value = null
    
    println("✅ Bunker login successful, user: ${userPubkey.take(16)}...")
    println("   Client pubkey: ${newNip46Client.clientPubkey.take(16)}...")

    // Connect to metadata relay first to load user profile faster
    connectToMetadataRelay()
    connect()

    return userPubkey
} 

    // Reconnect to bunker if disconnected
    private suspend fun reconnectBunker(): Boolean {
        val savedBunkerUrl = SecureStorage.getBunkerUrl() ?: return false
        val savedClientPrivateKey = SecureStorage.getBunkerClientPrivateKey()
        
        try {
            println("🔄 Attempting to reconnect bunker...")
            val bunkerInfo = parseBunkerUrl(savedBunkerUrl)
            
            // Use saved client private key to maintain session identity
            val newNip46Client = if (savedClientPrivateKey != null) {
                println("   Using saved client keypair for session continuity")
                Nip46Client(savedClientPrivateKey)
            } else {
                println("   No saved client key, generating new (will need re-authorization)")
                Nip46Client()
            }
            
            // Set up auth URL callback for re-authorization
            newNip46Client.onAuthUrl = { url ->
                println("🔐 Auth URL received for reconnection: $url")
                _authUrl.value = url
            }
            
            try {
                newNip46Client.connect(
                    remoteSignerPubkey = bunkerInfo.pubkey,
                    relays = bunkerInfo.relays,
                    secret = bunkerInfo.secret
                )
            } catch (e: Exception) {
                // "already connected" means the signer remembers us - this is success!
                if (e.message?.contains("already connected", ignoreCase = true) == true) {
                    println("✅ Signer reports already connected - reusing session")
                } else {
                    throw e
                }
            }
            
            nip46Client = newNip46Client
            _isBunkerConnected.value = true
            
            // Save client key if it was newly generated
            if (savedClientPrivateKey == null) {
                SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
            }
            
            // Verify pubkey
            try {
                val actualUserPubkey = newNip46Client.getPublicKey()
                val savedUserPubkey = SecureStorage.getBunkerUserPubkey()
                if (actualUserPubkey != savedUserPubkey) {
                    bunkerUserPubkey = actualUserPubkey
                    SecureStorage.saveBunkerUserPubkey(actualUserPubkey)
                }
            } catch (e: Exception) {
                // If getPublicKey fails but we have saved pubkey, use that
                val savedUserPubkey = SecureStorage.getBunkerUserPubkey()
                if (savedUserPubkey != null) {
                    println("⚠️ Could not verify pubkey, using saved: ${savedUserPubkey.take(16)}...")
                } else {
                    throw e
                }
            }
            
            println("✅ Bunker reconnected successfully")
            return true
        } catch (e: Exception) {
            println("❌ Bunker reconnection failed: ${e.message}")
            return false
        }
    }

    // Sign event using bunker or local keypair
    private suspend fun signEvent(event: Event): Event {
        return if (isBunkerLogin) {
            // Try to reconnect if bunker is not connected
            if (nip46Client == null) {
                val reconnected = reconnectBunker()
                if (!reconnected) {
                    throw Exception("Bunker not connected and reconnection failed. Please try logging in again.")
                }
            }
            
            val bunker = nip46Client ?: throw Exception("Bunker not connected")
            try {
                val eventJson = event.toJsonString()
                val signedEventJson = bunker.signEvent(eventJson)
                parseSignedEvent(signedEventJson)
            } catch (e: Exception) {
                // Handle permission errors - need to re-authorize
                if (e.message?.contains("no permission", ignoreCase = true) == true ||
                    e.message?.contains("not authorized", ignoreCase = true) == true ||
                    e.message?.contains("permission denied", ignoreCase = true) == true) {
                    
                    println("🔐 Permission denied - clearing session, please login again")
                    // Clear the bunker session so user can re-login
                    nip46Client?.disconnect()
                    nip46Client = null
                    _isBunkerConnected.value = false
                    SecureStorage.clearBunkerUrl()
                    SecureStorage.clearBunkerUserPubkey()
                    SecureStorage.clearBunkerClientPrivateKey()
                    isBunkerLogin = false
                    bunkerUserPubkey = null
                    _isLoggedIn.value = false
                    
                    throw Exception("Signing permission denied. Please login again and approve signing permission in your signer app.")
                }
                throw e
            }
        } else {
            val kp = keyPair ?: throw Exception("Not logged in")
            event.sign(kp)
        }
    }

    private fun parseSignedEvent(jsonString: String): Event {
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(jsonString).jsonObject
        
        return Event(
            id = obj["id"]?.jsonPrimitive?.content,
            pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: "",
            createdAt = obj["created_at"]?.jsonPrimitive?.long ?: 0L,
            kind = obj["kind"]?.jsonPrimitive?.int ?: 0,
            tags = obj["tags"]?.jsonArray?.map { tagArray ->
                tagArray.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList(),
            content = obj["content"]?.jsonPrimitive?.content ?: "",
            sig = obj["sig"]?.jsonPrimitive?.content
        )
    }
    
    // NEW: Load kind:10009 from Nostr
    private suspend fun loadJoinedGroupsFromNostr() {
        val pubKey = getPublicKey() ?: return
        val currentClient = metadataClient ?: run {
            println("⚠️ No metadata client available")
            return
        }
        
        try {
            kind10009Received = false
            eoseReceived = false
            
            val filter = buildJsonObject {
                putJsonArray("kinds") { add(10009) }
                putJsonArray("authors") { add(pubKey) }
                put("limit", 1)
            }
            
            val subId = "joined-groups-${epochMillis()}"
            kind10009SubId = subId

            val message = buildJsonArray {
                add("REQ")
                add(subId)
                add(filter)
            }.toString()
            
            currentClient.send(message)
            println("📥 Requesting kind:10009 for relay: ${_currentRelayUrl.value}")
            println("   SubId: $subId")
            println("   PubKey: ${pubKey.take(16)}...")
            
            var waitTime = 0
            while (!eoseReceived && waitTime < 5000) {
                kotlinx.coroutines.delay(500)
                waitTime += 500
            }
            
            val closeMsg = buildJsonArray {
                add("CLOSE")
                add(subId)
            }.toString()
            currentClient.send(closeMsg)
            println("🔒 Closed subscription: $subId")
            
            if (!kind10009Received) {
                println("⚠️ No kind:10009 event found on relay")
                val localGroups = SecureStorage.getJoinedGroupsForRelay(_currentRelayUrl.value)
                if (localGroups.isNotEmpty()) {
                    println("📤 Publishing local joined groups (${localGroups.size} groups) as kind:10009")
                    _joinedGroups.value = localGroups
                    allRelayGroups[_currentRelayUrl.value] = localGroups.toMutableSet()
                    publishJoinedGroupsList()
                } else {
                    println("ℹ️ No local joined groups to publish")
                }
            } else {
                println("✅ Successfully loaded kind:10009 with ${_joinedGroups.value.size} groups for current relay")
            }
        } catch (e: Exception) {
            println("❌ Failed to load joined groups: ${e.message}")
        }
    }

    // NEW: Publish kind:10009 to Nostr
    private suspend fun publishJoinedGroupsList() {
        val pubKey = getPublicKey() ?: run {
            println("⚠️ Cannot publish kind:10009 - not logged in")
            return
        }
        val currentClient = metadataClient ?: run {
            println("⚠️ Cannot publish kind:10009 - metadata client not connected")
            return
        }
        
        try {
            val currentRelayGroups = _joinedGroups.value
            allRelayGroups[_currentRelayUrl.value] = currentRelayGroups.toMutableSet()
            
            val tags = mutableListOf<List<String>>()
            allRelayGroups.forEach { (relayUrl, groupIds) ->
                groupIds.forEach { groupId ->
                    tags.add(listOf("group", groupId, relayUrl))
                }
            }
            
            println("🔄 Merging groups from ${allRelayGroups.size} relay(s):")
            allRelayGroups.forEach { (relay, groups) ->
                println("   • $relay: ${groups.size} group(s)")
            }
            
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 10009,
                tags = tags,
                content = ""
            )
            
            val signedEvent = signEvent(event)
            
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            
            currentClient.send(message)
            val totalGroups = tags.size
            println("📤 Published kind:10009 with $totalGroups total group(s) across ${allRelayGroups.size} relay(s)")
            println("   Current relay (${_currentRelayUrl.value}): ${currentRelayGroups.size} group(s)")
        } catch (e: Exception) {
            println("❌ Failed to publish joined groups: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun connectToMetadataRelay() {
        try {
            val relayUrl = metadataRelays[currentMetadataRelayIndex]
            println("🔗 Connecting to metadata relay: $relayUrl")

            val newMetadataClient = NostrGroupClient(relayUrl)
            metadataClient = newMetadataClient

            newMetadataClient.connect { msg ->
                handleMetadataMessage(msg, newMetadataClient)
            }

            newMetadataClient.waitForConnection()
            println("✅ Connected to metadata relay: $relayUrl")

            // Immediately fetch the logged-in user's metadata first (highest priority)
            val pubKey = getPublicKey()
            if (pubKey != null) {
                println("👤 Fetching current user metadata first...")
                newMetadataClient.requestMetadata(listOf(pubKey))
            }

            kotlinx.coroutines.delay(500)
            println("🔄 Loading kind:10009 joined groups...")

            loadJoinedGroupsFromNostr()
        } catch (e: Exception) {
            println("❌ Failed to connect to metadata relay: ${e.message}")
            if (currentMetadataRelayIndex < metadataRelays.size - 1) {
                currentMetadataRelayIndex++
                connectToMetadataRelay()
            }
        }
    } 

    private fun handleMetadataMessage(msg: String, client: NostrGroupClient) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray
            
            if (arr.size >= 2 && arr[0].jsonPrimitive.content == "EOSE") {
                val subId = arr[1].jsonPrimitive.content
                if (subId == kind10009SubId) {
                    eoseReceived = true
                    println("✅ EOSE received for kind:10009 subscription")
                }
                return
            }
            
            if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT") {
                val subId = arr[1].jsonPrimitive.content
                val event = arr[2].jsonObject
                val kind = event["kind"]?.jsonPrimitive?.int

                // Handle event_* subscriptions (fetched events by ID)
                if (subId.startsWith("event_")) {
                    val eventId = event["id"]?.jsonPrimitive?.content ?: return
                    val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
                    val content = event["content"]?.jsonPrimitive?.content ?: ""
                    val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                    val eventKind = kind ?: 1
                    val tags = event["tags"]?.jsonArray?.map { tagArray ->
                        tagArray.jsonArray.map { it.jsonPrimitive.content }
                    } ?: emptyList()

                    val cachedEvent = CachedEvent(
                        id = eventId,
                        pubkey = pubkey,
                        kind = eventKind,
                        content = content,
                        createdAt = createdAt,
                        tags = tags
                    )
                    _cachedEvents.value = _cachedEvents.value + (eventId to cachedEvent)
                    println("✅ Cached event ${eventId.take(8)}... (kind $eventKind)")
                    return
                }

                if (kind == 10009) {
                    kind10009Received = true
                    println("🎯 Received kind:10009 event")
                    val tags = event["tags"]?.jsonArray ?: return
                    
                    allRelayGroups.clear()
                    val currentRelayGroups = mutableSetOf<String>()
                    
                    tags.forEach { tag ->
                        val tagArray = tag.jsonArray
                        if (tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "group") {
                            val groupId = tagArray[1].jsonPrimitive.content
                            val relayUrl = tagArray.getOrNull(2)?.jsonPrimitive?.content
                            
                            if (relayUrl != null) {
                                allRelayGroups.getOrPut(relayUrl) { mutableSetOf() }.add(groupId)
                                
                                if (relayUrl == _currentRelayUrl.value) {
                                    currentRelayGroups.add(groupId)
                                    println("  ✅ $groupId (${_currentRelayUrl.value})")
                                } else {
                                    println("  📝 $groupId ($relayUrl) - stored for merging")
                                }
                            } else {
                                currentRelayGroups.add(groupId)
                                allRelayGroups.getOrPut(_currentRelayUrl.value) { mutableSetOf() }.add(groupId)
                                println("  ✅ $groupId (no relay specified, using current)")
                            }
                        }
                    }
                    
                    _joinedGroups.value = currentRelayGroups
                    SecureStorage.saveJoinedGroupsForRelay(_currentRelayUrl.value, currentRelayGroups)
                    println("💾 Saved ${currentRelayGroups.size} groups for current relay")
                    println("📊 Total groups across all relays: ${allRelayGroups.values.sumOf { it.size }}")
                    println("📊 Relays in kind:10009: ${allRelayGroups.keys.joinToString(", ")}")
                    return
                }
            }
        } catch (e: Exception) {
            println("⚠️ Error parsing metadata message: ${e.message}")
        }
        
        val userMetadata = client.parseUserMetadata(msg)
        if (userMetadata != null) {
            val (pubkey, metadata) = userMetadata
            _userMetadata.value = _userMetadata.value + (pubkey to metadata)
            println("✅ Loaded metadata for ${metadata.name ?: metadata.displayName ?: pubkey.take(8)}")
        }
    }

    suspend fun connect() {
        connect(_currentRelayUrl.value)
    }
    
    private suspend fun connect(relayUrl: String) {
        if (client != null || isConnecting) {
            println("⚠️ Already connected or connecting")
            return
        }
        
        isConnecting = true
        _connectionState.value = ConnectionState.Connecting
        
        try {
            val newClient = NostrGroupClient(relayUrl)
            client = newClient
            
            newClient.connect { msg ->
                println("📩 Received: $msg")
                handleMessage(msg, newClient)
            }
            
            newClient.waitForConnection()
            _connectionState.value = ConnectionState.Connected
            println("✅ Repository connected to $relayUrl")
            
            // Only send AUTH if using local keypair (not bunker)
            if (!isBunkerLogin) {
                keyPair?.let { kp ->
                    newClient.sendAuth(kp.privateKeyHex)
                }
            }
            newClient.requestGroups()
            
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            println("❌ Connection failed: ${e.message}")
            client = null
        } finally {
            isConnecting = false
        }
    }

    fun getPublicKey(): String? {
        return if (isBunkerLogin) {
            bunkerUserPubkey
        } else {
            keyPair?.publicKeyHex
        }
    }

    fun getPrivateKey(): String? {
        return if (isBunkerLogin) {
            null // Bunker doesn't expose private key
        } else {
            keyPair?.privateKeyHex
        }
    }
    
    fun isUsingBunker(): Boolean = isBunkerLogin
    
    fun isBunkerReady(): Boolean = isBunkerLogin && nip46Client != null
    
    suspend fun ensureBunkerConnected(): Boolean {
        if (!isBunkerLogin) return true // Not using bunker, nothing to do
        if (nip46Client != null) return true // Already connected
        return reconnectBunker()
    }

    suspend fun loginSuspend(privKey: String, pubKey: String) {
        // Clear any bunker session
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
        
        keyPair = KeyPair.fromPrivateKeyHex(privKey)
        SecureStorage.savePrivateKey(privKey)
        _isLoggedIn.value = true
        _isBunkerConnected.value = false
        // Connect to metadata relay first to load user profile faster
        connectToMetadataRelay()
        connect()
    }
    
    suspend fun logout() {
        disconnect()
        metadataClient?.disconnect()
        metadataClient = null
        
        // Clear bunker session but KEEP the client private key
        // This allows re-login with the same bunker URI
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        // NOTE: We intentionally do NOT clear BunkerClientPrivateKey here
        // This allows the user to re-login with the same bunker URI
        // The client private key will only be cleared when:
        // 1. User calls forgetBunkerConnection() explicitly
        // 2. User logs in with private key (loginSuspend)
        // 3. Permission is denied by signer
        
        SecureStorage.clearPrivateKey()
        SecureStorage.clearAllJoinedGroups()
        keyPair = null
        _isLoggedIn.value = false
        _isBunkerConnected.value = false
        _joinedGroups.value = emptySet()
        allRelayGroups.clear()
        println("👋 Logged out (bunker client key preserved for re-login)")
    }
    
    // Call this to completely forget the bunker connection
    // User will need a new bunker URI after this
    suspend fun forgetBunkerConnection() {
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
        _isBunkerConnected.value = false
        println("🗑️ Bunker connection completely forgotten - need new bunker URI")
    }

    suspend fun switchRelay(newRelayUrl: String) {
        println("🔄 Switching to relay: $newRelayUrl")
        
        disconnect()
        
        _currentRelayUrl.value = newRelayUrl
        SecureStorage.saveCurrentRelayUrl(newRelayUrl)
        
        _joinedGroups.value = SecureStorage.getJoinedGroupsForRelay(newRelayUrl)
        println("📂 Loaded ${_joinedGroups.value.size} local joined groups")
        
        connect(newRelayUrl)
        
        kind10009Received = false
        eoseReceived = false
        
        val currentMetadataClient = metadataClient
        if (currentMetadataClient == null) {
            println("🔄 Metadata client not connected, connecting...")
            connectToMetadataRelay()
            kotlinx.coroutines.delay(2000)
        } else {
            kotlinx.coroutines.delay(1000)
        }
        
        println("🔄 Loading kind:10009 for new relay...")
        loadJoinedGroupsFromNostr()
    }

    suspend fun requestUserMetadata(pubkeys: Set<String>) {
        val currentMetadataClient = metadataClient
        if (currentMetadataClient == null) {
            println("⚠️ Metadata client not connected, connecting now...")
            connectToMetadataRelay()
            metadataClient?.let {
                it.requestMetadata(pubkeys.toList())
            }
            return
        }

        println("📥 Requesting metadata for ${pubkeys.size} users: ${pubkeys.map { it.take(8) }}")
        currentMetadataClient.requestMetadata(pubkeys.toList())
    }

    suspend fun requestEventById(eventId: String) {
        // Skip if already cached
        if (_cachedEvents.value.containsKey(eventId)) {
            return
        }

        val currentMetadataClient = metadataClient
        if (currentMetadataClient == null) {
            println("⚠️ Metadata client not connected, connecting now...")
            connectToMetadataRelay()
            metadataClient?.let {
                it.requestEventById(eventId)
            }
            return
        }

        println("📥 Requesting event: ${eventId.take(8)}...")
        currentMetadataClient.requestEventById(eventId)
    }

    private fun handleMessage(msg: String, client: NostrGroupClient) {
        val groupMetadata = client.parseGroupMetadata(msg)
        if (groupMetadata != null && groupMetadata.name != null) {
            _groups.value = (_groups.value + groupMetadata).distinctBy { it.id }
            return
        }
        
        val userMetadata = client.parseUserMetadata(msg)
        if (userMetadata != null) {
            val (pubkey, metadata) = userMetadata
            _userMetadata.value = _userMetadata.value + (pubkey to metadata)
            println("✅ Loaded metadata from group relay for ${metadata.name ?: metadata.displayName ?: pubkey.take(8)}")
            return
        }
        
        val message = client.parseMessage(msg)
        if (message != null && (message.kind == 9 || message.kind == 9021 || message.kind == 9022)) {
            val groupId = extractGroupIdFromMessage(msg)
            if (groupId != null) {
                val currentMessages = _messages.value[groupId] ?: emptyList()
                _messages.value = _messages.value + (groupId to (currentMessages + message).distinctBy { it.id }.sortedBy { it.createdAt })
                
                if (!_userMetadata.value.containsKey(message.pubkey)) {
                    println("🔍 Requesting metadata for new user: ${message.pubkey.take(8)}")
                    CoroutineScope(Dispatchers.Default).launch {
                        requestUserMetadata(setOf(message.pubkey))
                    }
                }
                
                val eventType = when (message.kind) {
                    9 -> "message"
                    9021 -> "join"
                    9022 -> "leave"
                    else -> "event"
                }
                println("✅ Added $eventType to group $groupId from ${message.pubkey.take(8)}")
            }
        }
    } 

    private fun extractGroupIdFromMessage(msg: String): String? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray
            if (arr.size < 3) return null
            val event = arr[2].jsonObject
            val tags = event["tags"]?.jsonArray ?: return null
            
            tags.firstOrNull { tag ->
                val tagArray = tag.jsonArray
                tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "h"
            }?.jsonArray?.get(1)?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun joinGroup(groupId: String) {
        val currentClient = client ?: run {
            println("⚠️ Cannot join group - not connected")
            return
        }
        
        val pubKey = getPublicKey() ?: run {
            println("⚠️ Cannot join group - not logged in")
            return
        }
        
        try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9021,
                tags = listOf(
                    listOf("h", groupId)
                ),
                content = "/join"
            )

            println(event)
            
            val signedEvent = signEvent(event)
            
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            
            currentClient.send(message)
            
            _joinedGroups.value = _joinedGroups.value + groupId
            SecureStorage.saveJoinedGroupsForRelay(_currentRelayUrl.value, _joinedGroups.value)
            
            publishJoinedGroupsList()
            
            println("✅ Joined group $groupId on relay ${_currentRelayUrl.value}")
            
            requestGroupMessages(groupId)
            
        } catch (e: Exception) {
            println("❌ Failed to join group: ${e.message}")
            e.printStackTrace()
        }
    }
    
    suspend fun leaveGroup(groupId: String, reason: String? = null) {
        val currentClient = client ?: run {
            println("⚠️ Cannot leave group - not connected")
            return
        }
        
        val pubKey = getPublicKey() ?: run {
            println("⚠️ Cannot leave group - not logged in")
            return
        }
        
        try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9022,
                tags = listOf(
                    listOf("h", groupId)
                ),
                content = reason.orEmpty()
            )

            println(event)
            
            val signedEvent = signEvent(event)
            
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            
            currentClient.send(message)
            
            _joinedGroups.value = _joinedGroups.value - groupId
            SecureStorage.saveJoinedGroupsForRelay(_currentRelayUrl.value, _joinedGroups.value)
            
            publishJoinedGroupsList()
            
            _messages.value = _messages.value - groupId
            
            println("✅ Left group $groupId on relay ${_currentRelayUrl.value}")
            
        } catch (e: Exception) {
            println("❌ Failed to leave group: ${e.message}")
            e.printStackTrace()
        }
    }
    
    fun isGroupJoined(groupId: String): Boolean {
        return _joinedGroups.value.contains(groupId)
    }
    
    suspend fun requestGroupMessages(groupId: String, channel: String? = null) {
        val currentClient = client
        if (currentClient == null) {
            println("⚠️ Not connected, connecting first...")
            connect()
            return requestGroupMessages(groupId, channel)
        }
        
        currentClient.requestGroupMessages(groupId, channel)
    }

    suspend fun sendMessage(groupId: String, content: String, channel: String? = null) {
        val currentClient = client ?: run {
            println("⚠️ Cannot send message - not connected")
            return
        }
        
        val pubKey = getPublicKey() ?: run {
            println("⚠️ Cannot send message - not logged in")
            return
        }
        
        try {
            val tags = mutableListOf(listOf("h", groupId))
            if (channel != null && channel != "general") {
                tags.add(listOf("channel", channel))
            }
            
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9,
                tags = tags,
                content = content
            )
            
            val signedEvent = signEvent(event)
            
            val eventJson = signedEvent.toJsonObject()
            val message = buildJsonArray {
                add("EVENT")
                add(eventJson)
            }.toString()
            
            currentClient.send(message)
            println("📤 Sent message to group $groupId${if (channel != null && channel != "general") " in channel $channel" else " (general)"}: $content")
            
        } catch (e: Exception) {
            println("❌ Failed to send message: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> {
        return _messages.value[groupId] ?: emptyList()
    }
    
    suspend fun disconnect() {
        client?.disconnect()
        client = null
        _connectionState.value = ConnectionState.Disconnected
        _groups.value = emptyList()
        _messages.value = emptyMap()
        isConnecting = false
    }
}

// Helper function for parsing bunker URLs
data class BunkerInfo(
    val pubkey: String,
    val relays: List<String>,
    val secret: String?
)

fun parseBunkerUrl(url: String): BunkerInfo {
    val trimmed = url.trim()
    
    require(trimmed.startsWith("bunker://")) {
        "Invalid bunker URL: must start with bunker://"
    }

    val withoutScheme = trimmed.removePrefix("bunker://")
    val parts = withoutScheme.split("?", limit = 2)
    
    val pubkey = parts[0]
    require(pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Invalid pubkey in bunker URL"
    }

    val relays = mutableListOf<String>()
    var secret: String? = null

    if (parts.size > 1) {
        val queryParams = parts[1].split("&")
        for (param in queryParams) {
            val kv = param.split("=", limit = 2)
            if (kv.size == 2) {
                val key = kv[0]
                val value = kv[1].urlDecode()
                when (key) {
                    "relay" -> relays.add(value)
                    "secret" -> secret = value
                }
            }
        }
    }

    require(relays.isNotEmpty()) {
        "Bunker URL must contain at least one relay"
    }

    return BunkerInfo(pubkey, relays, secret)
}

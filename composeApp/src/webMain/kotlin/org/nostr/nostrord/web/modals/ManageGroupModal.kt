package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.screens.group.GroupAccessCopy
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.HierarchyPrompt
import org.nostr.nostrord.ui.screens.group.addChannelPrompt
import org.nostr.nostrord.ui.screens.group.detachChannelPrompt
import org.nostr.nostrord.ui.screens.group.hierarchyView
import org.nostr.nostrord.ui.screens.group.makeRootPrompt
import org.nostr.nostrord.ui.screens.group.moveUnderPrompt
import org.nostr.nostrord.ui.screens.group.movedChildOrder
import org.nostr.nostrord.ui.screens.group.pendingJoinRequests
import org.nostr.nostrord.ui.screens.group.reparentGroup
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.shortNpub
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.GroupAvatarUploadRow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierRow
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.confirmDialog
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.searchInput
import org.nostr.nostrord.web.components.tabItem
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.navigation.pushHome
import org.nostr.nostrord.web.navigation.pushRoute
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface ManageGroupModalProps : Props {
    var group: GroupMetadata
    var onClose: () -> Unit

    /** Tab to open on first render (case-insensitive name, e.g. "members"). Defaults to Info. */
    var initialTab: String?

    /**
     * Relay hosting this group (route-carried). Scopes the member/request reads so a
     * same-id group on another relay doesn't leak into this modal; null falls back to
     * the focused relay (legacy callers).
     */
    var relayUrl: String?
}

private enum class ManageTab(val label: String, val ic: Ic) {
    Info("Info", Ic.Settings),
    Members("Members", Ic.People),
    Invites("Invites", Ic.Link),
    Requests("Requests", Ic.Shield),
    Hierarchy("Hierarchy", Ic.AccountTree),
    Danger("Danger", Ic.Warning),
}

/**
 * Unified admin "Manage group" modal (port of the prototype GroupManage): a left tab
 * nav (Info / Members / Invites / Requests / Danger) over the group's real moderation
 * features. Tabs adapt to the group type (Requests is auto-only for open groups), and the
 * destructive delete lives on its own Danger tab so it stays out of the edit-and-save flow.
 */
val ManageGroupModal =
    FC<ManageGroupModalProps> { props ->
        val group = props.group
        val (tab, setTab) =
            useState {
                ManageTab.entries.firstOrNull { it.name.equals(props.initialTab, ignoreCase = true) } ?: ManageTab.Info
            }
        useEscClose { props.onClose() }

        // The Hierarchy tab only makes sense where the relay advertises NIP-29 subgroup support
        // (nip29:{subgroups:true} in its NIP-11); hide it elsewhere.
        val focusedRelay = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val relayUrl = props.relayUrl ?: focusedRelay
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        val supportsSubgroups =
            (relayMetadata[relayUrl] ?: relayMetadata[relayUrl.normalizeRelayUrl()])?.supportsSubgroups == true
        val visibleTabs = ManageTab.entries.filter { it != ManageTab.Hierarchy || supportsSubgroups }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card manage-modal")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +(if (group.parent != null) "Manage channel" else "Manage group")
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("manage-layout")
                    div {
                        className = ClassName("manage-nav")
                        visibleTabs.forEach { t ->
                            button {
                                key = t.name
                                className = ClassName(if (t == tab) "manage-nav-item selected" else "manage-nav-item")
                                onClick = { setTab(t) }
                                icon(t.ic)
                                span { +t.label }
                            }
                        }
                    }
                    div {
                        className = ClassName("manage-content")
                        when (tab) {
                            ManageTab.Info ->
                                ManageInfoSection {
                                    this.group = group
                                    this.relayUrl = relayUrl
                                    onClose = props.onClose
                                }
                            ManageTab.Members ->
                                ManageMembersSection {
                                    groupId = group.id
                                    this.relayUrl = relayUrl
                                }
                            ManageTab.Invites -> ManageInvitesSection { groupId = group.id }
                            ManageTab.Requests ->
                                ManageRequestsSection {
                                    groupId = group.id
                                    isOpen = group.isOpen
                                    this.relayUrl = relayUrl
                                }
                            ManageTab.Hierarchy ->
                                ManageHierarchySection {
                                    this.group = group
                                    onClose = props.onClose
                                }
                            ManageTab.Danger ->
                                ManageDangerSection {
                                    this.group = group
                                    this.relayUrl = relayUrl
                                    onClose = props.onClose
                                }
                        }
                    }
                }
            }
        }
    }

// ---- Info ----

private external interface ManageInfoProps : Props {
    var group: GroupMetadata
    var relayUrl: String
    var onClose: () -> Unit
}

private val ManageInfoSection =
    FC<ManageInfoProps> { props ->
        val group = props.group
        val (name, setName) = useState { group.name ?: "" }
        val (about, setAbout) = useState { group.about ?: "" }
        val (picture, setPicture) = useState { group.picture ?: "" }
        val (isPrivate, setIsPrivate) = useState { !group.isPublic }
        val (isClosed, setIsClosed) = useState { !group.isOpen }
        val (isRestricted, setIsRestricted) = useState { group.isRestricted }
        val (isHidden, setIsHidden) = useState { group.isHidden }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        val relayHost = props.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
        val relayIconUrl =
            (relayMetadata[props.relayUrl] ?: relayMetadata[props.relayUrl.normalizeRelayUrl()])?.icon

        GroupAvatarUploadRow {
            pictureUrl = picture
            seed = group.id
            this.name = name
            onPictureChange = { setPicture(it) }
            onError = { setError(it) }
        }
        div {
            className = ClassName("field-label")
            +"Name"
        }
        input {
            className = ClassName("modal-input")
            placeholder = "#example"
            value = name
            onChange = { e ->
                setName(e.currentTarget.value)
                setError(null)
            }
        }
        if (relayHost.isNotBlank()) {
            div {
                className = ClassName("group-side-banner-relay manage-relay-chip")
                WebAvatar {
                    url = relayIconUrl
                    seed = props.relayUrl
                    this.name = relayHost
                    kind = AvatarKind.RELAY
                    cls = "group-side-banner-relay-icon"
                }
                span {
                    className = ClassName("manage-relay-chip-host")
                    +relayHost
                }
            }
        }
        div {
            className = ClassName("field-label")
            +"Description"
        }
        textarea {
            className = ClassName("modal-textarea")
            placeholder = "What is this group about?"
            rows = 3
            value = about
            onChange = { e -> setAbout(e.currentTarget.value) }
        }

        div {
            className = ClassName("access-section-title")
            +"ACCESS"
        }
        accessToggle(Ic.Lock, GroupAccessCopy.PRIVATE_LABEL, GroupAccessCopy.PRIVATE_DESC, isPrivate) { setIsPrivate(!isPrivate) }
        accessToggle(Ic.Block, GroupAccessCopy.CLOSED_LABEL, GroupAccessCopy.CLOSED_DESC, isClosed) { setIsClosed(!isClosed) }
        accessToggle(Ic.Send, GroupAccessCopy.RESTRICTED_LABEL, GroupAccessCopy.RESTRICTED_DESC, isRestricted) { setIsRestricted(!isRestricted) }
        accessToggle(Ic.VisibilityOff, GroupAccessCopy.HIDDEN_LABEL, GroupAccessCopy.HIDDEN_DESC, isHidden) { setIsHidden(!isHidden) }

        if (error != null) {
            div {
                className = ClassName("modal-error")
                +error
            }
        }

        div {
            className = ClassName("modal-footer")
            button {
                className = ClassName("btn-primary")
                disabled = name.isBlank() || busy
                onClick = {
                    if (name.isBlank()) {
                        setError("Group name is required.")
                    } else {
                        setError(null)
                        setBusy(true)
                        launchApp {
                            val result =
                                AppModule.nostrRepository.editGroup(
                                    groupId = group.id,
                                    name = name.trim(),
                                    about = about.trim().ifBlank { null },
                                    isPrivate = isPrivate,
                                    isClosed = isClosed,
                                    isRestricted = isRestricted,
                                    isHidden = isHidden,
                                    picture = picture.trim().ifBlank { null },
                                )
                            setBusy(false)
                            when (result) {
                                is Result.Success -> props.onClose()
                                is Result.Error -> setError(result.error.message.ifBlank { "Failed to update group." })
                            }
                        }
                    }
                }
                +(if (busy) "Saving…" else "Save Changes")
            }
        }
    }

// ---- Danger ----

private val ManageDangerSection =
    FC<ManageInfoProps> { props ->
        val group = props.group
        val noun = if (group.parent != null) "channel" else "group"
        val (confirmDelete, setConfirmDelete) = useState { false }
        val (deleting, setDeleting) = useState { false }
        val (error, setError) = useState<String?> { null }

        div {
            className = ClassName("manage-danger")
            div {
                className = ClassName("manage-danger-head")
                icon(Ic.Warning)
                span {
                    className = ClassName("manage-danger-title")
                    +"Delete $noun"
                }
            }
            div {
                className = ClassName("manage-danger-desc")
                +(
                    if (confirmDelete) {
                        "Are you sure? This permanently deletes the $noun from the relay and cannot be undone."
                    } else {
                        "This permanently deletes the $noun from the relay. This cannot be undone."
                    }
                    )
            }
            if (error != null) {
                div {
                    className = ClassName("modal-error")
                    +error
                }
            }
            div {
                className = ClassName("manage-danger-actions")
                if (!confirmDelete) {
                    button {
                        className = ClassName("btn-danger")
                        onClick = { setConfirmDelete(true) }
                        icon(Ic.Delete)
                        +"Delete $noun"
                    }
                } else {
                    button {
                        className = ClassName("btn-secondary")
                        disabled = deleting
                        onClick = { setConfirmDelete(false) }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-danger")
                        disabled = deleting
                        onClick = {
                            setDeleting(true)
                            setError(null)
                            launchApp {
                                when (val result = AppModule.nostrRepository.deleteGroup(group.id)) {
                                    is Result.Success -> {
                                        props.onClose()
                                        // Deleting a channel lands on its parent group; only a
                                        // root delete goes home.
                                        val parentId = group.parent
                                        if (parentId != null) {
                                            pushRoute(GroupRoute(props.relayUrl, parentId))
                                        } else {
                                            pushHome()
                                        }
                                    }
                                    is Result.Error -> {
                                        setDeleting(false)
                                        setError(result.error.message.ifBlank { "Failed to delete $noun." })
                                    }
                                }
                            }
                        }
                        icon(Ic.Delete)
                        +(if (deleting) "Deleting…" else "Confirm delete")
                    }
                }
            }
        }
    }

// ---- Members ----

private external interface ManageGroupIdProps : Props {
    var groupId: String

    /** Relay hosting the group; scopes VM reads (same-id groups on other relays stay out). */
    var relayUrl: String?
}

private val ManageMembersSection =
    FC<ManageGroupIdProps> { props ->
        val repo = AppModule.nostrRepository
        // Same shared VM as the native manage modal: moderation actions surface relay
        // rejections/timeouts in moderationError instead of failing silently (#174).
        val vm = useViewModel("${props.relayUrl}|${props.groupId}") { GroupViewModel(repo, props.groupId, props.relayUrl) }
        val moderationError = useStateFlow(vm.moderationError)
        val moderationBusy = useStateFlow(vm.moderationBusy)
        val members = useStateFlow(vm.groupMembers)[props.groupId].orEmpty()
        val admins = useStateFlow(vm.groupAdmins)[props.groupId].orEmpty().toSet()
        val userMetadata = useStateFlow(repo.userMetadata)
        val (tab, setTab) = useState { "All" }
        val (query, setQuery) = useState { "" }
        // (pubkey, action) whose promote / demote / remove is awaiting confirmation.
        val (confirmAction, setConfirmAction) = useState<Pair<String, String>?> { null }
        // Pubkey whose row action menu (the chevron dropdown) is currently open.
        val (openMenu, setOpenMenu) = useState<String?> { null }
        val myPubkey = repo.getPublicKey()

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: shortNpub(pubkey)
        }

        val adminCount = members.count { it in admins }
        val memberCount = members.size - adminCount

        val filtered =
            members
                .filter {
                    when (tab) {
                        "Admins" -> it in admins
                        "Members" -> it !in admins
                        else -> true
                    }
                }
                .filter {
                    query.isBlank() ||
                        nameOf(it).contains(query, ignoreCase = true) ||
                        it.contains(query, ignoreCase = true) ||
                        Nip19.encodeNpub(it).contains(query, ignoreCase = true)
                }

        searchInput(
            placeholder = "Search members...",
            value = query,
            onChange = { setQuery(it) },
            compact = true,
        )
        // Per-category counts ride on the filter tabs (All / Admins / Members), styled like the
        // home tab strip (segmented pill) instead of plain chips.
        div {
            className = ClassName("tab-strip mod-tab-strip")
            tabItem(tab == "All", null, "All · ${members.size}") { setTab("All") }
            tabItem(tab == "Admins", null, "Admins · $adminCount") { setTab("Admins") }
            tabItem(tab == "Members", null, "Members · $memberCount") { setTab("Members") }
        }
        moderationError?.let { err ->
            div {
                className = ClassName("modal-error")
                +err
            }
        }
        div {
            className = ClassName("mod-list member-list")
            if (filtered.isEmpty()) {
                div {
                    className = ClassName("mod-empty")
                    +"No members found"
                }
            }
            filtered.forEach { pubkey ->
                val isAdmin = pubkey in admins
                div {
                    key = pubkey
                    className = ClassName("mod-row member-card")
                    WebAvatar {
                        url = userMetadata[pubkey]?.picture
                        seed = pubkey
                        name = nameOf(pubkey)
                        cls = "mod-avatar"
                    }
                    div {
                        className = ClassName("mod-member-meta")
                        div {
                            className = ClassName("mod-member-line")
                            span {
                                className = ClassName("mod-name")
                                +nameOf(pubkey)
                            }
                            if (pubkey == myPubkey) {
                                span {
                                    className = ClassName("mod-you")
                                    +"YOU"
                                }
                            }
                        }
                        span {
                            className = ClassName("mod-npub")
                            +(Nip19.encodeNpub(pubkey).take(20) + "...")
                        }
                    }
                    if (isAdmin) {
                        span {
                            className = ClassName("member-admin")
                            +"ADMIN"
                        }
                    }
                    // No self-demote / self-remove: a NIP-29 relay only accepts moderation
                    // (kind 9000/9001) from an admin, so demoting yourself is one-way; you could
                    // not re-promote yourself and may be locked out. Use Leave group.
                    if (pubkey != myPubkey) {
                        div {
                            className = ClassName("mod-menu-wrap")
                            button {
                                className = ClassName("mod-menu-btn")
                                onClick = { setOpenMenu(if (openMenu == pubkey) null else pubkey) }
                                icon(Ic.ExpandMore)
                            }
                            if (openMenu == pubkey) {
                                // Full-screen click-catcher so a click anywhere else closes the menu.
                                div {
                                    className = ClassName("mod-menu-backdrop")
                                    onClick = { setOpenMenu(null) }
                                }
                                div {
                                    className = ClassName("mod-menu")
                                    // Gated while a kind:9000/9001 awaits its OK so a slow relay
                                    // can't collect a second, duplicate action.
                                    button {
                                        className = ClassName("mod-menu-item")
                                        disabled = moderationBusy
                                        onClick = {
                                            setOpenMenu(null)
                                            setConfirmAction(pubkey to if (isAdmin) "demote" else "promote")
                                        }
                                        icon(Ic.Shield)
                                        span { +(if (isAdmin) "Remove Admin Role" else "Promote to Admin") }
                                    }
                                    button {
                                        className = ClassName("mod-menu-item danger")
                                        disabled = moderationBusy
                                        onClick = {
                                            setOpenMenu(null)
                                            setConfirmAction(pubkey to "remove")
                                        }
                                        icon(Ic.Close)
                                        span { +"Remove from Group" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        confirmAction?.let { (pubkey, action) ->
            val title =
                when (action) {
                    "promote" -> "Promote to Admin"
                    "demote" -> "Remove Admin Role"
                    else -> "Remove from Group"
                }
            val desc =
                when (action) {
                    "promote" -> "${nameOf(pubkey)} will be able to manage members and group settings."
                    "demote" -> "${nameOf(pubkey)} will lose admin privileges."
                    else -> "${nameOf(pubkey)} will be removed from the group."
                }
            confirmDialog(
                title = title,
                body = desc,
                confirmLabel = "Confirm",
                danger = action == "remove",
                onCancel = { setConfirmAction(null) },
                onConfirm = {
                    setConfirmAction(null)
                    when (action) {
                        "promote" -> vm.promoteToAdmin(pubkey)
                        "demote" -> vm.demoteFromAdmin(pubkey)
                        else -> vm.removeUser(pubkey)
                    }
                },
            )
        }
    }

// ---- Hierarchy ----

private external interface ManageHierarchyProps : Props {
    var group: GroupMetadata

    /** Close the whole Manage modal (row navigation leaves the screen). */
    var onClose: () -> Unit
}

/**
 * Hierarchy tab: move the group under a parent (making it a channel), promote it back to a
 * root group, and manage its channel list (add / detach). Every change is one full-state
 * kind:9002 via editGroup (PUT semantics); a parent must live on the same relay, and the
 * candidate list excludes the group itself and its own descendants so no cycle can form.
 */
private external interface GroupPickerProps : Props {
    var placeholder: String
    var candidates: List<GroupMetadata>
    var disabled: Boolean
    var onPick: (GroupMetadata) -> Unit
}

/**
 * Collapsed trigger that expands into a searchable candidate panel (web analogue of the
 * Compose GroupPickerDropdown): filter field past a handful of entries, avatar rows,
 * picking collapses and reports the group.
 */
private val GroupPicker =
    FC<GroupPickerProps> { props ->
        val (open, setOpen) = useState { false }
        val (query, setQuery) = useState { "" }
        div {
            className = ClassName("hierarchy-pick")
            button {
                className = ClassName("modal-input hierarchy-pick-trigger")
                disabled = props.disabled
                onClick = {
                    setOpen(!open)
                    setQuery("")
                }
                span { +props.placeholder }
                icon(if (open) Ic.ExpandLess else Ic.ExpandMore)
            }
            if (open && !props.disabled) {
                div {
                    className = ClassName("hierarchy-pick-panel")
                    if (props.candidates.size > 6) {
                        searchInput(
                            placeholder = "Search groups...",
                            value = query,
                            onChange = { setQuery(it) },
                            compact = true,
                            autoFocus = true,
                        )
                    }
                    div {
                        className = ClassName("hierarchy-pick-list")
                        val shown = props.candidates.filter {
                            query.isBlank() ||
                                (it.name ?: "").contains(query, ignoreCase = true) ||
                                it.id.contains(query, ignoreCase = true)
                        }
                        if (shown.isEmpty()) {
                            div {
                                className = ClassName("mod-empty")
                                +"No matches"
                            }
                        }
                        shown.forEach { g ->
                            val gName = g.name?.takeIf { it.isNotBlank() } ?: g.id
                            button {
                                key = g.id
                                className = ClassName("hierarchy-pick-row")
                                onClick = {
                                    setOpen(false)
                                    setQuery("")
                                    props.onPick(g)
                                }
                                WebAvatar {
                                    url = g.picture
                                    seed = g.id
                                    name = gName
                                    kind = AvatarKind.GROUP
                                    cls = "hierarchy-row-avatar"
                                }
                                span {
                                    className = ClassName("mod-name")
                                    +gName
                                }
                            }
                        }
                    }
                }
            }
        }
    }

private data class PendingHierarchyOp(
    val prompt: HierarchyPrompt,
    val target: GroupMetadata,
    val op: GroupManager.ParentOp,
    val fail: String,
)

private val ManageHierarchySection =
    FC<ManageHierarchyProps> { props ->
        val repo = AppModule.nostrRepository
        val relayUrl = useStateFlow(repo.currentRelayUrl)
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val childrenByParent = useStateFlow(repo.childrenByParent)
        val groupAdmins = useStateFlow(repo.groupAdmins)
        val myPubkey = repo.getPublicKey()
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }
        val (pending, setPending) = useState<PendingHierarchyOp?> { null }
        val (showCreate, setShowCreate) = useState { false }

        val relayGroups = groupsByRelay[relayUrl].orEmpty()
        val byId = relayGroups.associateBy { it.id }
        // Prefer the live metadata over the props snapshot: a reorder/reparent lands as a
        // fresh kind:39000 while the modal is open.
        val group = byId[props.group.id] ?: props.group
        val view = hierarchyView(group.id, group, relayGroups, childrenByParent, groupAdmins, myPubkey)
        val groupName = group.name?.takeIf { it.isNotBlank() } ?: group.id

        // Channel rows need each child's kind:39000 (name + full state for the detach PUT).
        val missingKey = view.missingChildMeta.joinToString(",")
        useEffect(missingKey) {
            view.missingChildMeta.forEach { repo.refreshGroupMetadata(it) }
        }

        fun run(op: PendingHierarchyOp) {
            setBusy(true)
            setError(null)
            launchApp {
                val r = reparentGroup(repo, op.target, op.op)
                setBusy(false)
                if (r is Result.Error) setError(r.error.message.ifBlank { op.fail })
            }
        }

        // Reordering re-declares the parent's child tags, so it needs THIS group's admin and a
        // channel list in sync with the declared tags (an in-flight attach/detach desyncs them
        // briefly, and reorderChildren rejects a non-permutation).
        val canReorder = myPubkey != null &&
            myPubkey in groupAdmins[group.id].orEmpty() &&
            view.childIds.size > 1 &&
            group.children.toSet() == view.childIds.toSet()

        fun moveChild(sid: String, delta: Int) {
            val newOrder = movedChildOrder(view.childIds, sid, delta) ?: return
            setBusy(true)
            setError(null)
            launchApp {
                val r = repo.reorderChildren(group.id, newOrder)
                setBusy(false)
                if (r is Result.Error) setError(r.error.message.ifBlank { "Failed to reorder channels." })
            }
        }

        if (view.parentId != null) {
            // Channel view: where this channel lives (breadcrumb), its siblings, then the
            // move actions. The parent crumb navigates like the channel rows do.
            div {
                className = ClassName("access-section-title")
                +"LOCATION"
            }
            div {
                className = ClassName("hierarchy-crumb")
                val parentMeta = byId[view.parentId]
                val parentLabel = view.parentName ?: view.parentId!!
                button {
                    className = ClassName("hierarchy-crumb-item")
                    title = "Open parent group"
                    onClick = {
                        props.onClose()
                        pushRoute(GroupRoute(relayUrl, view.parentId!!))
                    }
                    WebAvatar {
                        url = parentMeta?.picture
                        seed = view.parentId!!
                        name = parentLabel
                        kind = AvatarKind.GROUP
                        cls = "hierarchy-row-avatar"
                    }
                    span { +parentLabel }
                }
                span {
                    className = ClassName("hierarchy-crumb-sep")
                    +"\u203a"
                }
                div {
                    className = ClassName("hierarchy-crumb-item current")
                    WebAvatar {
                        url = group.picture
                        seed = group.id
                        name = groupName
                        kind = AvatarKind.GROUP
                        cls = "hierarchy-row-avatar"
                    }
                    span { +groupName }
                }
            }

            div {
                className = ClassName("access-section-title")
                +"SIBLINGS (${view.siblingIds.size})"
            }
            div {
                className = ClassName("mod-list")
                view.siblingIds.forEach { sid ->
                    val isSelf = sid == group.id
                    val m = byId[sid]
                    val name = m?.name?.takeIf { it.isNotBlank() } ?: "${sid.take(12)}\u2026"
                    div {
                        key = sid
                        className = ClassName(if (isSelf) "mod-row hierarchy-sibling-current" else "mod-row")
                        if (isSelf) {
                            WebAvatar {
                                url = m?.picture
                                seed = sid
                                this.name = name
                                kind = AvatarKind.GROUP
                                cls = "hierarchy-row-avatar"
                            }
                            span {
                                className = ClassName("mod-name")
                                +name
                            }
                            span {
                                className = ClassName("mod-muted")
                                +"this channel"
                            }
                        } else {
                            button {
                                className = ClassName("hierarchy-open-btn")
                                title = "Open channel"
                                onClick = {
                                    props.onClose()
                                    pushRoute(GroupRoute(relayUrl, sid))
                                }
                                WebAvatar {
                                    url = m?.picture
                                    seed = sid
                                    this.name = name
                                    kind = AvatarKind.GROUP
                                    cls = "hierarchy-row-avatar"
                                }
                                span {
                                    className = ClassName("mod-name")
                                    +name
                                }
                            }
                        }
                    }
                }
            }

            div {
                className = ClassName("access-section-title")
                +"MOVE"
            }
        } else {
            div {
                className = ClassName("access-section-title")
                +"PARENT"
            }
            div {
                className = ClassName("hierarchy-current")
                +"Current: "
                span {
                    className = ClassName("hierarchy-current-value")
                    +"Root group"
                }
            }
        }
        div {
            className = ClassName("hierarchy-row")
            GroupPicker {
                placeholder = when {
                    !view.canMove -> "Detach its channels to move this group"
                    view.parentCandidates.isEmpty() -> "No other groups on this relay"
                    else -> "Move under..."
                }
                candidates = view.parentCandidates
                disabled = busy || view.parentCandidates.isEmpty() || !view.canMove
                onPick = { target ->
                    setPending(
                        PendingHierarchyOp(
                            moveUnderPrompt(groupName, target.name?.takeIf { it.isNotBlank() } ?: target.id),
                            group,
                            GroupManager.ParentOp.SetTo(target.id),
                            "Failed to update hierarchy.",
                        ),
                    )
                }
            }
            if (view.parentId != null) {
                button {
                    className = ClassName("btn-secondary")
                    disabled = busy
                    onClick = {
                        setPending(
                            PendingHierarchyOp(
                                makeRootPrompt(groupName, view.parentName),
                                group,
                                GroupManager.ParentOp.Detach,
                                "Failed to convert to root group.",
                            ),
                        )
                    }
                    +"Make root group"
                }
            }
        }
        if (view.parentId != null) {
            div {
                className = ClassName("modal-subtitle")
                +(
                    "Making this a root group keeps its members, messages and settings; " +
                        "it leaves ${view.parentName ?: "its parent"} and gets its own spot in the rail."
                    )
            }
        }
        if (error != null) {
            div {
                className = ClassName("modal-error")
                +error
            }
        }

        // Channels don't have channels (single-level hierarchy): the section only
        // exists on root groups.
        if (view.parentId == null) {
            div {
                className = ClassName("hierarchy-head")
                div {
                    className = ClassName("access-section-title")
                    +"CHANNELS (${view.childIds.size})"
                }
                button {
                    className = ClassName("btn-secondary")
                    disabled = busy
                    onClick = { setShowCreate(true) }
                    +"Create channel"
                }
            }
            div {
                className = ClassName("mod-list")
                if (view.childIds.isEmpty()) {
                    div {
                        className = ClassName("mod-empty")
                        +"No channels."
                    }
                }
                view.childIds.forEachIndexed { index, sid ->
                    val sub = byId[sid]
                    val subName = sub?.name?.takeIf { it.isNotBlank() } ?: "${sid.take(12)}\u2026"
                    div {
                        key = sid
                        className = ClassName("mod-row")
                        button {
                            className = ClassName("hierarchy-open-btn")
                            title = "Open channel"
                            onClick = {
                                props.onClose()
                                pushRoute(GroupRoute(relayUrl, sid))
                            }
                            WebAvatar {
                                url = sub?.picture
                                seed = sid
                                name = subName
                                kind = AvatarKind.GROUP
                                cls = "hierarchy-row-avatar"
                            }
                            span {
                                className = ClassName("mod-name")
                                +subName
                            }
                        }
                        div {
                            className = ClassName("mod-actions")
                            if (canReorder) {
                                button {
                                    className = ClassName("mod-btn")
                                    title = "Move up"
                                    disabled = busy || index == 0
                                    onClick = { moveChild(sid, -1) }
                                    icon(Ic.ExpandLess)
                                }
                                button {
                                    className = ClassName("mod-btn")
                                    title = "Move down"
                                    disabled = busy || index == view.childIds.lastIndex
                                    onClick = { moveChild(sid, +1) }
                                    icon(Ic.ExpandMore)
                                }
                            }
                            // Detaching edits the CHILD's kind:9002, so it needs its admin key.
                            if (sub != null && myPubkey != null && myPubkey in groupAdmins[sid].orEmpty()) {
                                button {
                                    className = ClassName("mod-btn")
                                    disabled = busy
                                    onClick = {
                                        setPending(
                                            PendingHierarchyOp(
                                                detachChannelPrompt(subName),
                                                sub,
                                                GroupManager.ParentOp.Detach,
                                                "Failed to detach channel.",
                                            ),
                                        )
                                    }
                                    +"Detach"
                                }
                            } else {
                                span {
                                    className = ClassName("mod-muted")
                                    +(if (sub == null) "loading\u2026" else "channel admin only")
                                }
                            }
                        }
                    }
                }
            }
            div {
                className = ClassName("hierarchy-row")
                GroupPicker {
                    placeholder = if (view.childCandidates.isEmpty()) "No groups you admin to add" else "Add an existing group as a channel..."
                    candidates = view.childCandidates
                    disabled = busy || view.childCandidates.isEmpty()
                    onPick = { target ->
                        setPending(
                            PendingHierarchyOp(
                                addChannelPrompt(target.name?.takeIf { it.isNotBlank() } ?: target.id, groupName),
                                target,
                                GroupManager.ParentOp.SetTo(group.id),
                                "Failed to add channel.",
                            ),
                        )
                    }
                }
            }
            div {
                className = ClassName("modal-subtitle")
                +"Only childless root groups you administer on this relay can be added as channels; channels can't contain channels. Detaching a channel turns it back into a root group."
            }
        }

        pending?.let { p ->
            confirmDialog(
                title = p.prompt.title,
                body = p.prompt.message,
                confirmLabel = p.prompt.confirmLabel,
                onCancel = { setPending(null) },
                onConfirm = {
                    setPending(null)
                    run(p)
                },
            )
        }

        if (showCreate) {
            CreateGroupModal {
                onClose = { setShowCreate(false) }
                subgroup = true
                parentGroupId = group.id
                this.relayUrl = relayUrl
            }
        }
    }

// ---- Invites ----

private val ManageInvitesSection =
    FC<ManageGroupIdProps> { props ->
        val repo = AppModule.nostrRepository
        val msgs = useStateFlow(repo.messages)[props.groupId].orEmpty()
        val relayUrl = useStateFlow(repo.currentRelayUrl)
        val relayMetadata = useStateFlow(repo.relayMetadata)
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        val revoked =
            msgs.filter { it.kind == 9005 }
                .flatMap { m -> m.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) } }
                .toSet()
        val codes =
            msgs.filter { it.kind == 9009 && it.id !in revoked }
                .mapNotNull { m ->
                    val code = m.tags.firstOrNull { it.firstOrNull() == "code" }?.getOrNull(1) ?: return@mapNotNull null
                    Triple(code, m.id, m.createdAt)
                }
                .sortedByDescending { it.third }
        val relayPubkey = relayMetadata[relayUrl]?.groupNaddrAuthor ?: relayMetadata[relayUrl.trimEnd('/')]?.groupNaddrAuthor

        button {
            className = ClassName("btn-primary block")
            disabled = busy
            onClick = {
                setBusy(true)
                setError(null)
                launchApp {
                    val result = repo.createInviteCode(props.groupId)
                    setBusy(false)
                    if (result is Result.Error) {
                        // Surface the relay's reason; the common case is a relay that does not
                        // allow kind:9009 (invite codes).
                        val raw = (result.error.cause?.message ?: result.error.message)
                            .removePrefix("blocked: ")
                            .removePrefix("error: ")
                        setError(
                            if (raw.contains("9009") || raw.contains("not allowed", ignoreCase = true)) {
                                "This relay does not support invite codes."
                            } else {
                                raw.ifBlank { "Failed to create invite code." }
                            },
                        )
                    }
                }
            }
            +(if (busy) "Creating…" else "Create invite code")
        }
        if (error != null) {
            div {
                className = ClassName("modal-error")
                +error
            }
        }
        div {
            className = ClassName("access-section-title")
            +"ACTIVE CODES (${codes.size})"
        }
        div {
            className = ClassName("mod-list")
            if (codes.isEmpty()) {
                div {
                    className = ClassName("mod-empty")
                    +"No active invite codes"
                }
            }
            codes.forEach { (code, eventId, _) ->
                div {
                    key = eventId
                    className = ClassName("mod-row invite-code-row")
                    div {
                        className = ClassName("invite-code-head")
                        span {
                            className = ClassName("mod-code")
                            +code
                        }
                        div {
                            className = ClassName("mod-actions")
                            button {
                                className = ClassName("mod-btn")
                                onClick = { copyToClipboard(code) }
                                +"Copy code"
                            }
                            button {
                                className = ClassName("mod-btn danger")
                                onClick = { launchApp { repo.revokeInviteCode(props.groupId, eventId) } }
                                +"Revoke"
                            }
                        }
                    }
                    IdentifierRow { ids = groupIdentifiers(relayUrl, props.groupId, relayPubkey, code) }
                }
            }
        }
    }

// ---- Requests ----

private external interface ManageRequestsProps : Props {
    var groupId: String
    var isOpen: Boolean

    /** Relay hosting the group; scopes the 9021 feed (same-id groups elsewhere stay out). */
    var relayUrl: String?
}

private val ManageRequestsSection =
    FC<ManageRequestsProps> { props ->
        // Hooks must run unconditionally and in a stable order, so they precede the
        // open-group early return (React errors with "rendered more hooks" otherwise).
        val repo = AppModule.nostrRepository
        // Through the relay-scoped VM, not repo.messages: the raw bucket is keyed by bare
        // id and would show a same-id group's join requests from another relay.
        val vm = useViewModel("${props.relayUrl}|${props.groupId}") { GroupViewModel(repo, props.groupId, props.relayUrl) }
        val msgs = useStateFlow(vm.messages)[props.groupId].orEmpty()
        val members = useStateFlow(vm.groupMembers)[props.groupId].orEmpty().toSet()
        val userMetadata = useStateFlow(repo.userMetadata)

        val pending = pendingJoinRequests(msgs, members)

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: shortNpub(pubkey)
        }

        if (pending.isEmpty()) {
            // Open groups auto-join, so an empty queue is the normal state.
            div {
                className = ClassName("mod-empty")
                +(
                    if (props.isOpen) {
                        "Open group: people join automatically, so there is no request queue."
                    } else {
                        "No pending requests"
                    }
                    )
            }
        } else {
            // Header with the count and a one-tap Accept all. Open groups still surface these
            // because some relays leave a kind:9021 pending on a leave + rejoin; the admin lets
            // them back in here (open-group policy is to accept everyone).
            div {
                className = ClassName("mod-requests-header")
                span {
                    className = ClassName("mod-requests-title")
                    +"${if (props.isOpen) "Rejoining" else "Requests"} (${pending.size})"
                }
                button {
                    className = ClassName("mod-btn primary")
                    onClick = { launchApp { pending.forEach { repo.addUser(props.groupId, it.pubkey) } } }
                    +"Accept all"
                }
            }
            if (props.isOpen) {
                div {
                    className = ClassName("mod-note")
                    +"These people left and asked to rejoin. Open groups accept everyone, so you can let them back in."
                }
            }
            div {
                className = ClassName("mod-list")
                pending.forEach { req ->
                    div {
                        key = req.id
                        className = ClassName("mod-row")
                        WebAvatar {
                            url = userMetadata[req.pubkey]?.picture
                            seed = req.pubkey
                            name = nameOf(req.pubkey)
                            cls = "mod-avatar"
                        }
                        span {
                            className = ClassName("mod-name")
                            +nameOf(req.pubkey)
                        }
                        div {
                            className = ClassName("mod-actions")
                            button {
                                className = ClassName("mod-btn primary")
                                onClick = { launchApp { repo.addUser(props.groupId, req.pubkey) } }
                                +"Approve"
                            }
                            button {
                                className = ClassName("mod-btn danger")
                                onClick = { launchApp { repo.rejectJoinRequest(props.groupId, req.id) } }
                                +"Reject"
                            }
                        }
                    }
                }
            }
        }
    }

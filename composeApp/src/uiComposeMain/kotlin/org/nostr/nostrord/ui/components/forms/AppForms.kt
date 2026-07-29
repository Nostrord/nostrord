package org.nostr.nostrord.ui.components.forms

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

/**
 * Form design system — the Compose counterpart of the web's `.form-*` / `.input-*` /
 * `.tab` component classes (label, hint, error banner, icon input, segmented tabs),
 * styled after the prototype's Field/Input/Tabs components. One vocabulary on both
 * render trees, so screens stop hand-rolling OutlinedTextField color blocks and
 * pill tab strips.
 */

/** Small uppercase bold label above an input (web `.form-label`). */
/**
 * Input text style for form fields: 14sp to match the web .modal-input (the 16sp default
 * read oversized). Use on raw OutlinedTextField call sites that don't go through AppTextField.
 */
@Composable
fun appFieldTextStyle() = LocalTextStyle.current.copy(fontSize = 14.sp)

/**
 * The single OutlinedTextField color set for form fields (web .modal-input parity): a #131217
 * floating-surface container in both themes, brand border on focus. Every labeled field shares
 * this, so the input background never drifts per screen.
 */
@Composable
fun appFieldColors(container: Color = NostrordColors.BackgroundFloating) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = NostrordColors.TextContent,
    unfocusedTextColor = NostrordColors.TextContent,
    focusedBorderColor = NostrordColors.Primary,
    unfocusedBorderColor = NostrordColors.Divider,
    cursorColor = NostrordColors.Primary,
    focusedContainerColor = container,
    unfocusedContainerColor = container,
    focusedPlaceholderColor = NostrordColors.TextMuted,
    unfocusedPlaceholderColor = NostrordColors.TextMuted,
)

/**
 * Web `.modal-input` content padding (10px vertical, 12px horizontal). Material's
 * `OutlinedTextField` floors itself at ~56dp and exposes no `contentPadding`, so every
 * standardized field goes through [AppField] (BasicTextField + DecorationBox) to hit this
 * exact density on native.
 */
private val AppFieldContentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

/** 8dp radius, matching the web `.modal-input` border-radius. */
private val AppFieldShape = RoundedCornerShape(8.dp)

/**
 * The single standardized form field (web `.modal-input` / `.modal-textarea` / `.modal-select`
 * parity). Built on BasicTextField + `OutlinedTextFieldDefaults.DecorationBox` so the vertical
 * density matches the web (10x12 padding, 14sp value + placeholder) instead of Material's tall
 * 56dp default. Drives single-line inputs, multi-line textareas (`singleLine = false` + `minLines`)
 * and read-only select anchors (`readOnly = true` + a [trailingIcon], with `.menuAnchor(...)` on
 * [modifier]). Labels are supplied separately via [FormLabel] above the field.
 */
@Composable
fun AppField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    // Fields on a raised surface need one of their own: the default matches the page, so on a
    // panel painted the same colour the value would read as loose text in a hairline box.
    containerColor: Color = NostrordColors.BackgroundFloating,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    // Drop the inherited lineHeight (bodyLarge = 24sp): a standalone placeholder Text honors it,
    // but BasicTextField measures a single line at the font's natural height, so the empty field
    // rendered taller than the filled one. Unspecified makes both use the font height and match.
    val textStyle =
        LocalTextStyle.current.copy(
            color = NostrordColors.TextContent,
            fontSize = 14.sp,
            lineHeight = TextUnit.Unspecified,
        )
    val visual = if (masked) PasswordVisualTransformation() else VisualTransformation.None
    val colors = appFieldColors(containerColor)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        cursorBrush = SolidColor(NostrordColors.Primary),
        visualTransformation = visual,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions =
        KeyboardActions(
            onDone = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
            onSend = { onImeAction?.invoke() },
            onSearch = { onImeAction?.invoke() },
        ),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = visual,
                interactionSource = interactionSource,
                placeholder =
                placeholder?.let {
                    {
                        Text(
                            it,
                            // Same TextStyle as the value (only the color differs) so the field
                            // is the exact same height empty or filled; a placeholder with a
                            // different line height made the box grow/shrink as you typed.
                            style = textStyle.copy(color = NostrordColors.TextMuted),
                            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                colors = colors,
                contentPadding = AppFieldContentPadding,
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = enabled,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = AppFieldShape,
                    )
                },
            )
        },
    )
}

@Composable
fun FormLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = NostrordColors.TextSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Muted helper text below an input (web `.form-hint`). */
@Composable
fun FormHint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = NostrordColors.TextMuted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * Red error banner at the top of a form (web `.form-error`): renders nothing for a
 * null message, and carries its own spacing below.
 */
@Composable
fun FormError(message: String?) {
    message?.let {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.shapeSmall,
            color = NostrordColors.Error.copy(alpha = 0.1f),
        ) {
            Text(
                text = it,
                color = NostrordColors.Error,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * Prototype-styled input (web `.input-group` + `.input`): floating surface,
 * transparent border with the brand border on focus, optional [label] above and
 * [hint] below. [masked] applies password masking; [onDone] wires the IME action.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLines: Int = 1,
    enabled: Boolean = true,
    containerColor: Color = NostrordColors.BackgroundFloating,
    onDone: (() -> Unit)? = null,
    // Opt-in: when set, Escape clears focus and invokes this (filters clear with it).
    onEscape: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    Column(modifier = modifier) {
        label?.let { FormLabel(it) }
        AppField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = maxLines == 1,
            maxLines = maxLines,
            modifier =
            Modifier
                .fillMaxWidth()
                // Desktop keyboard parity with the web: Tab / Shift+Tab move focus
                // (Compose text fields consume Tab by default) and a hardware Enter
                // submits whenever the field has an [onDone].
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Tab -> {
                            focusManager.moveFocus(
                                if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next,
                            )
                            true
                        }
                        onDone != null && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                            onDone()
                            true
                        }
                        onEscape != null && event.key == Key.Escape -> {
                            onEscape()
                            focusManager.clearFocus()
                            true
                        }
                        else -> false
                    }
                },
            leadingIcon =
            leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = NostrordColors.TextMuted,
                    )
                }
            },
            trailingIcon = trailingIcon,
            masked = masked,
            keyboardType = keyboardType,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
            onImeAction = onDone,
            enabled = enabled,
            containerColor = containerColor,
        )
        hint?.let { FormHint(it) }
    }
}

/** Density variants shared by the inputs, mirroring the web `.input-group` / `.input-group.sm`. */
enum class InputSize { Default, Compact }

/**
 * Standardized search / filter input (web `searchInput` over `.input-group`): floating surface with
 * a transparent border that lifts to the brand color on focus (the shared focus effect every input
 * should use), an optional leading [icon] and an optional [trailing] slot (a clear or close button).
 * [size] switches between the default and the compact `.input-group.sm` density. Escape runs
 * [onEscape] when set; a hardware Enter runs [onDone].
 *
 * This is the single search component: pass `icon = null` for the no-icon variant and
 * `size = InputSize.Compact` for dense rows (DM / member sidebars). Labeled form fields keep using
 * [AppTextField], which shares the same floating-surface focus treatment.
 */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    size: InputSize = InputSize.Default,
    icon: ImageVector? = Icons.Default.Search,
    trailing: (@Composable () -> Unit)? = null,
    autoFocus: Boolean = false,
    containerColor: Color = NostrordColors.BackgroundFloating,
    onEscape: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    val compact = size == InputSize.Compact
    val shape = if (compact) RoundedCornerShape(8.dp) else NostrordShapes.inputShape
    val horizontalPadding = if (compact) 10.dp else 12.dp
    // Fixed height so the box never resizes between the placeholder and typed text (their line
    // metrics differ); content is vertically centered inside.
    val inputHeight = if (compact) 36.dp else 44.dp
    val gap = if (compact) 6.dp else 8.dp
    val fontSize = if (compact) 13.sp else 14.sp
    val iconSize = if (compact) 16.dp else 18.dp

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(if (focused) NostrordColors.Primary else Color.Transparent)
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    Row(
        modifier =
        modifier
            .height(inputHeight)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(iconSize),
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    color = NostrordColors.TextMuted,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = NostrordColors.TextContent, fontSize = fontSize),
                cursorBrush = SolidColor(NostrordColors.Primary),
                keyboardOptions =
                KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                interactionSource = interactionSource,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            onEscape != null && event.key == Key.Escape -> {
                                onEscape()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
        trailing?.invoke()
    }
}

/** One entry of [AppSegmentedTabs]. */
data class SegmentedTab(
    val label: String,
    val icon: ImageVector? = null,
)

/**
 * Segmented pill tabs (web `.tab-strip` + `.tab`): floating container, brand pill on
 * the selected item, inactive text lifting to primary on hover. Used for the login
 * method tabs and the bunker QR/URL toggle.
 */
@Composable
fun AppSegmentedTabs(
    tabs: List<SegmentedTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false,
) {
    // Tighter label padding when three or more tabs share the row.
    val horizontalPadding = if (tabs.size >= 3) 8.dp else 12.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NostrordShapes.shapeMedium,
        color = NostrordColors.BackgroundFloating,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selectedIndex
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val backgroundColor by animateColorAsState(
                    when {
                        isSelected -> NostrordColors.Primary
                        isHovered -> NostrordColors.HoverBackground
                        else -> Color.Transparent
                    },
                )
                val contentColor =
                    when {
                        isSelected -> Color.White
                        isHovered -> NostrordColors.TextPrimary
                        else -> NostrordColors.TextMuted
                    }

                Surface(
                    modifier =
                    Modifier
                        .weight(1f)
                        .clip(NostrordShapes.shapeSmall)
                        .hoverable(interactionSource)
                        .clickable { onSelect(index) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = NostrordShapes.shapeSmall,
                    color = backgroundColor,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tab.icon?.let {
                            Icon(
                                imageVector = it,
                                // Icon-only mode drops the label, so the icon carries the name.
                                contentDescription = if (iconOnly) tab.label else null,
                                modifier = Modifier.size(18.dp),
                                tint = contentColor,
                            )
                            if (!iconOnly) Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (!iconOnly) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

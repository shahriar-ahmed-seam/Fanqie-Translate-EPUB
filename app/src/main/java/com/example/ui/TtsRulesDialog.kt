package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tts.rule.RuleValidationResult
import com.example.tts.rule.TtsRule
import com.example.tts.rule.TtsRuleType

/**
 * Reusable Settings Card section for managing TTS Speech Rules in SettingsScreen style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsRulesSection(
    rules: List<TtsRule>,
    onSaveRule: (TtsRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String) -> Unit,
    onReorderRule: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isEmbeddedInDialog: Boolean = false
) {
    var selectedFilterIndex by rememberSaveable { mutableIntStateOf(0) }
    var editingRule by remember { mutableStateOf<TtsRule?>(null) }
    var isCreatingNewRule by remember { mutableStateOf(false) }

    val filterOptions = listOf("All", "Skip", "Regex", "Replace")

    val filteredRules = remember(rules, selectedFilterIndex) {
        when (selectedFilterIndex) {
            1 -> rules.filter { it.ruleType == TtsRuleType.SKIP }
            2 -> rules.filter { it.ruleType == TtsRuleType.SKIP_REGEX }
            3 -> rules.filter { it.ruleType == TtsRuleType.REPLACE }
            else -> rules
        }
    }

    val content = @Composable {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isEmbeddedInDialog) 0.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            if (isEmbeddedInDialog) {
                // Compact header inside reader dialog to avoid duplicate title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${rules.size} rule${if (rules.size != 1) "s" else ""} configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = { isCreatingNewRule = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("tts_section_add_rule_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Rule", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                // Full header in SettingsScreen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Spellcheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Speech Rules",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${rules.size} rule${if (rules.size != 1) "s" else ""} configured",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = { isCreatingNewRule = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("tts_section_add_rule_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Rule", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Horizontally Scrollable Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(filterOptions) { index, title ->
                    val count = when (index) {
                        1 -> rules.count { it.ruleType == TtsRuleType.SKIP }
                        2 -> rules.count { it.ruleType == TtsRuleType.SKIP_REGEX }
                        3 -> rules.count { it.ruleType == TtsRuleType.REPLACE }
                        else -> rules.size
                    }
                    FilterChip(
                        selected = selectedFilterIndex == index,
                        onClick = { selectedFilterIndex = index },
                        label = { Text("$title ($count)", style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = if (selectedFilterIndex == index) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.testTag("tts_filter_chip_${title.lowercase()}")
                    )
                }
            }

            // Rules List or Empty State
            if (filteredRules.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (rules.isEmpty()) "No speech rules yet" else "No ${filterOptions[selectedFilterIndex]} rules found",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (rules.isEmpty())
                                "Create rules to skip patterns or adjust pronunciation during TTS playback."
                            else
                                "Change or clear the category filter to view other rules.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (rules.isEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { isCreatingNewRule = true },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Speech Rule")
                            }
                        } else if (selectedFilterIndex != 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = { selectedFilterIndex = 0 },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Show All Rules")
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredRules.forEachIndexed { index, rule ->
                        TtsRuleItemCard(
                            rule = rule,
                            orderNumber = index + 1,
                            canMoveUp = index > 0,
                            canMoveDown = index < filteredRules.size - 1,
                            onToggle = { onToggleRule(rule.id) },
                            onEdit = { editingRule = rule },
                            onDelete = { onDeleteRule(rule.id) },
                            onMoveUp = { onReorderRule(rule.id, true) },
                            onMoveDown = { onReorderRule(rule.id, false) }
                        )
                    }
                }
            }
        }
    }

    if (isEmbeddedInDialog) {
        content()
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            content()
        }
    }

    // Add or Edit Rule Dialog
    if (isCreatingNewRule || editingRule != null) {
        val initialType = when (selectedFilterIndex) {
            1 -> TtsRuleType.SKIP
            2 -> TtsRuleType.SKIP_REGEX
            3 -> TtsRuleType.REPLACE
            else -> TtsRuleType.SKIP
        }
        val ruleToEdit = editingRule ?: TtsRule(
            ruleType = initialType,
            pattern = "",
            isRegex = initialType == TtsRuleType.SKIP_REGEX,
            bookId = null
        )

        TtsEditRuleDialog(
            initialRule = ruleToEdit,
            isNew = isCreatingNewRule,
            onDismiss = {
                isCreatingNewRule = false
                editingRule = null
            },
            onSave = { savedRule ->
                onSaveRule(savedRule)
                isCreatingNewRule = false
                editingRule = null
            }
        )
    }
}

/**
 * Modal dialog wrapper for managing rules (used from ReaderScreen).
 */
@Composable
fun TtsRulesDialog(
    rules: List<TtsRule>,
    currentBookId: String? = null,
    onDismiss: () -> Unit,
    onSaveRule: (TtsRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String) -> Unit,
    onReorderRule: ((String, Boolean) -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.90f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume touches */ },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speech Rules",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Scrollable Rules Section
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        TtsRulesSection(
                            rules = rules,
                            onSaveRule = onSaveRule,
                            onDeleteRule = onDeleteRule,
                            onToggleRule = onToggleRule,
                            onReorderRule = { id, up -> onReorderRule?.invoke(id, up) },
                            isEmbeddedInDialog = true
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual Rule Card clearly distinguishing Skip, Regex, and Replace rules.
 * Structured with:
 * 1. Top row: Type badge + mode chips on left, Switch on right.
 * 2. Middle row: Full-width text body with multi-line wrapping (handles very long words/regex gracefully).
 * 3. Bottom row: Reorder arrows on left, Edit & Delete actions on right.
 * Entire card is clickable to Edit.
 */
@Composable
private fun TtsRuleItemCard(
    rule: TtsRule,
    orderNumber: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tts_rule_card_${rule.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.isEnabled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. Top Row: Type badge + mode tags on Left, Enable Switch on Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Badges & mode pills
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    when (rule.ruleType) {
                        TtsRuleType.SKIP -> {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = if (rule.isEnabled) 0.9f else 0.4f)
                            ) {
                                Text(
                                    text = "SKIP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                        TtsRuleType.SKIP_REGEX -> {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (rule.isEnabled) 0.9f else 0.4f)
                            ) {
                                Text(
                                    text = "REGEX",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                        TtsRuleType.REPLACE -> {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (rule.isEnabled) 0.9f else 0.4f)
                            ) {
                                Text(
                                    text = "REPLACE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    if (rule.ruleType != TtsRuleType.SKIP_REGEX && rule.wholeWord) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = "Word",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (rule.caseSensitive) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = "Aa",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Switch
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier
                        .scale(0.82f)
                        .testTag("tts_rule_switch_${rule.id}")
                )
            }

            // 2. Full-width Body: Content with high visual contrast and multi-line wrapping
            when (rule.ruleType) {
                TtsRuleType.SKIP -> {
                    Text(
                        text = "\"${rule.pattern}\"",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (rule.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                TtsRuleType.SKIP_REGEX -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = rule.pattern,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = if (rule.isEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                TtsRuleType.REPLACE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "\"${rule.pattern}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (rule.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "replaces with",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (rule.isEnabled) 1f else 0.5f)
                        )
                        Text(
                            text = "\"${rule.replacement}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (rule.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 3. Bottom Action Row: Reorder on Left, Edit & Delete on Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reorder controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("tts_rule_move_up_${rule.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move up",
                            modifier = Modifier.size(20.dp),
                            tint = if (canMoveUp) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }

                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("tts_rule_move_down_${rule.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move down",
                            modifier = Modifier.size(20.dp),
                            tint = if (canMoveDown) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                }

                // Edit & Delete actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("tts_rule_edit_button_${rule.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit rule",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("tts_rule_delete_button_${rule.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete rule",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/**
 * Responsive, simplified Dialog for Adding or Editing a speech rule.
 * - Sticky Header with title and close button.
 * - Scrollable middle body with only necessary controls (rule type, inputs, toggles).
 * - Sticky Footer with Cancel and Save buttons (always visible and reachable above keyboard).
 * - Fully responsive with WindowInsets and IME padding for split-screen, landscape, and small phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsEditRuleDialog(
    initialRule: TtsRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (TtsRule) -> Unit
) {
    var ruleType by rememberSaveable { mutableStateOf(initialRule.ruleType) }
    var pattern by rememberSaveable { mutableStateOf(initialRule.pattern) }
    var replacement by rememberSaveable { mutableStateOf(initialRule.replacement) }
    var wholeWord by rememberSaveable { mutableStateOf(initialRule.wholeWord) }
    var caseSensitive by rememberSaveable { mutableStateOf(initialRule.caseSensitive) }
    var isEnabled by rememberSaveable { mutableStateOf(initialRule.isEnabled) }

    // Validation
    val validation = remember(pattern, ruleType) {
        val draft = initialRule.copy(
            ruleType = ruleType,
            pattern = pattern,
            replacement = replacement,
            isRegex = ruleType == TtsRuleType.SKIP_REGEX
        )
        draft.validate()
    }

    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val isCompactHeight = screenHeightDp < 500
    val maxDialogHeight = (screenHeightDp * 0.90f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .padding(
                    horizontal = 16.dp,
                    vertical = if (isCompactHeight) 8.dp else 16.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 540.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* prevent dismissal when tapping card */ },
                shape = RoundedCornerShape(if (isCompactHeight) 20.dp else 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 1. Sticky Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = if (isCompactHeight) 20.dp else 24.dp,
                                end = if (isCompactHeight) 12.dp else 16.dp,
                                top = if (isCompactHeight) 12.dp else 18.dp,
                                bottom = if (isCompactHeight) 8.dp else 12.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isNew) "Add Speech Rule" else "Edit Speech Rule",
                            style = if (isCompactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 2. Scrollable Body: Form Controls only (no test preview or tutorial clutter)
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(
                                horizontal = if (isCompactHeight) 20.dp else 24.dp,
                                vertical = if (isCompactHeight) 12.dp else 16.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 12.dp else 16.dp)
                    ) {
                        // Rule Type Selector (Skip, Regex Skip, Pronunciation)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Rule Type",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = ruleType == TtsRuleType.SKIP,
                                    onClick = { ruleType = TtsRuleType.SKIP },
                                    label = { Text("Skip", maxLines = 1) },
                                    leadingIcon = if (ruleType == TtsRuleType.SKIP) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("tts_chip_type_skip")
                                )
                                FilterChip(
                                    selected = ruleType == TtsRuleType.SKIP_REGEX,
                                    onClick = { ruleType = TtsRuleType.SKIP_REGEX },
                                    label = { Text("Regex", maxLines = 1) },
                                    leadingIcon = if (ruleType == TtsRuleType.SKIP_REGEX) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("tts_chip_type_regex")
                                )
                                FilterChip(
                                    selected = ruleType == TtsRuleType.REPLACE,
                                    onClick = { ruleType = TtsRuleType.REPLACE },
                                    label = { Text("Replace", maxLines = 1) },
                                    leadingIcon = if (ruleType == TtsRuleType.REPLACE) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("tts_chip_type_replace")
                                )
                            }
                        }

                        // Type-specific input fields
                        when (ruleType) {
                            TtsRuleType.SKIP -> {
                                OutlinedTextField(
                                    value = pattern,
                                    onValueChange = { pattern = it },
                                    label = { Text("Word or Phrase to Skip") },
                                    placeholder = { Text("e.g. Tomato") },
                                    isError = validation is RuleValidationResult.Invalid && pattern.isNotBlank(),
                                    supportingText = if (validation is RuleValidationResult.Invalid && pattern.isNotBlank()) {
                                        { Text((validation as RuleValidationResult.Invalid).reason, color = MaterialTheme.colorScheme.error) }
                                    } else null,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        imeAction = ImeAction.Done
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("tts_field_pattern"),
                                    maxLines = 3
                                )
                            }
                            TtsRuleType.SKIP_REGEX -> {
                                OutlinedTextField(
                                    value = pattern,
                                    onValueChange = { pattern = it },
                                    label = { Text("Regular Expression") },
                                    placeholder = { Text("e.g. \\[.*?\\]") },
                                    isError = validation is RuleValidationResult.Invalid && pattern.isNotBlank(),
                                    supportingText = if (validation is RuleValidationResult.Invalid && pattern.isNotBlank()) {
                                        { Text((validation as RuleValidationResult.Invalid).reason, color = MaterialTheme.colorScheme.error) }
                                    } else null,
                                    keyboardOptions = KeyboardOptions(
                                        autoCorrectEnabled = false,
                                        keyboardType = KeyboardType.Ascii,
                                        imeAction = ImeAction.Done
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("tts_field_pattern"),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    maxLines = 3
                                )
                            }
                            TtsRuleType.REPLACE -> {
                                OutlinedTextField(
                                    value = pattern,
                                    onValueChange = { pattern = it },
                                    label = { Text("Original Text") },
                                    placeholder = { Text("e.g. Tom") },
                                    isError = validation is RuleValidationResult.Invalid && pattern.isNotBlank(),
                                    supportingText = if (validation is RuleValidationResult.Invalid && pattern.isNotBlank()) {
                                        { Text((validation as RuleValidationResult.Invalid).reason, color = MaterialTheme.colorScheme.error) }
                                    } else null,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("tts_field_pattern"),
                                    maxLines = 3
                                )

                                OutlinedTextField(
                                    value = replacement,
                                    onValueChange = { replacement = it },
                                    label = { Text("Spoken Replacement") },
                                    placeholder = { Text("e.g. Jack") },
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        imeAction = ImeAction.Done
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("tts_field_replacement"),
                                    maxLines = 3
                                )
                            }
                        }

                        // Matching Mode Toggles
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (ruleType != TtsRuleType.SKIP_REGEX) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .toggleable(
                                            value = wholeWord,
                                            onValueChange = { wholeWord = it },
                                            role = Role.Switch
                                        )
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Whole Word Only", style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = wholeWord,
                                        onCheckedChange = null,
                                        modifier = Modifier.testTag("tts_toggle_whole_word")
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .toggleable(
                                        value = caseSensitive,
                                        onValueChange = { caseSensitive = it },
                                        role = Role.Switch
                                    )
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Case Sensitive", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = caseSensitive,
                                    onCheckedChange = null,
                                    modifier = Modifier.testTag("tts_toggle_case_sensitive")
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .toggleable(
                                        value = isEnabled,
                                        onValueChange = { isEnabled = it },
                                        role = Role.Switch
                                    )
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = null,
                                    modifier = Modifier.testTag("tts_toggle_enabled")
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 3. Sticky Footer: Cancel and Save (Always visible and reachable above keyboard)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (isCompactHeight) 20.dp else 24.dp,
                                vertical = if (isCompactHeight) 10.dp else 14.dp
                            ),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("tts_edit_dialog_cancel")
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (validation is RuleValidationResult.Valid) {
                                    onSave(
                                        initialRule.copy(
                                            ruleType = ruleType,
                                            pattern = pattern.trim(),
                                            replacement = if (ruleType == TtsRuleType.REPLACE) replacement.trim() else "",
                                            isRegex = ruleType == TtsRuleType.SKIP_REGEX,
                                            wholeWord = if (ruleType == TtsRuleType.SKIP_REGEX) false else wholeWord,
                                            caseSensitive = caseSensitive,
                                            isEnabled = isEnabled
                                        )
                                    )
                                }
                            },
                            enabled = pattern.isNotBlank() && validation is RuleValidationResult.Valid,
                            modifier = Modifier.testTag("tts_edit_dialog_save")
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

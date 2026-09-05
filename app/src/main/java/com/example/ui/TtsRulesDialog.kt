package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tts.rule.RuleValidationResult
import com.example.tts.rule.TtsRule
import com.example.tts.rule.TtsRuleType
import com.example.tts.rule.TtsTextProcessor

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
    modifier: Modifier = Modifier
) {
    var selectedFilterIndex by rememberSaveable { mutableIntStateOf(0) }
    var editingRule by remember { mutableStateOf<TtsRule?>(null) }
    var isCreatingNewRule by remember { mutableStateOf(false) }

    val filterOptions = listOf("All", "Skip", "Regex Skip", "Pronunciation")

    val filteredRules = remember(rules, selectedFilterIndex) {
        when (selectedFilterIndex) {
            1 -> rules.filter { it.ruleType == TtsRuleType.SKIP }
            2 -> rules.filter { it.ruleType == TtsRuleType.SKIP_REGEX }
            3 -> rules.filter { it.ruleType == TtsRuleType.REPLACE }
            else -> rules
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
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
                            text = "TTS Speech Rules",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Customize pronunciation and omit words or regex patterns from speech",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { isCreatingNewRule = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("tts_section_add_rule_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Rule", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filter Chips: All, Skip, Regex Skip, Pronunciation
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                filterOptions.forEachIndexed { index, title ->
                    val count = when (index) {
                        1 -> rules.count { it.ruleType == TtsRuleType.SKIP }
                        2 -> rules.count { it.ruleType == TtsRuleType.SKIP_REGEX }
                        3 -> rules.count { it.ruleType == TtsRuleType.REPLACE }
                        else -> rules.size
                    }
                    FilterChip(
                        selected = selectedFilterIndex == index,
                        onClick = { selectedFilterIndex = index },
                        label = { Text("$title ($count)", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Rules List
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
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (rules.isEmpty()) "No custom speech rules configured yet." else "No rules match this filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Skip rules remove unwanted text; Pronunciation rules replace text before speaking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Manage Speech Rules",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    item {
                        TtsRulesSection(
                            rules = rules,
                            onSaveRule = onSaveRule,
                            onDeleteRule = onDeleteRule,
                            onToggleRule = onToggleRule,
                            onReorderRule = { id, up -> onReorderRule?.invoke(id, up) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual Rule Card clearly distinguishing Skip, Regex Skip, and Pronunciation.
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.isEnabled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Reorder Up/Down arrows
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Move up",
                        modifier = Modifier.size(16.dp),
                        tint = if (canMoveUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Move down",
                        modifier = Modifier.size(16.dp),
                        tint = if (canMoveDown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                }
            }

            // Rule Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Type Badge: clearly distinguishing Skip, Regex Skip, Pronunciation
                    when (rule.ruleType) {
                        TtsRuleType.SKIP -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "SKIP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        TtsRuleType.SKIP_REGEX -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "REGEX SKIP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                        TtsRuleType.REPLACE -> {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "PRONUNCIATION",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Matching mode / case tags
                    if (rule.ruleType != TtsRuleType.SKIP_REGEX && rule.wholeWord) {
                        Text(
                            text = "Whole word",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (rule.caseSensitive) {
                        Text(
                            text = "Case-sensitive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Rule Content Representation
                when (rule.ruleType) {
                    TtsRuleType.SKIP -> {
                        Text(
                            text = "\"${rule.pattern}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TtsRuleType.SKIP_REGEX -> {
                        Text(
                            text = rule.pattern,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TtsRuleType.REPLACE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "\"${rule.pattern}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "replaces with",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "\"${rule.replacement}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Controls: Edit, Delete, Enable/Disable Switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit rule",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete rule",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.height(28.dp)
                )
            }
        }
    }
}

/**
 * Sub-dialog for Adding or Editing a rule with live regex validation and test sandbox.
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

    // Test Sandbox Sample Text
    var testSample by rememberSaveable {
        mutableStateOf(
            if (pattern.isNotBlank()) "Sample text containing $pattern in a sentence."
            else "Tom ate a Tomato while [reading chapter 1]."
        )
    }

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

    // Live Test Output
    val testOutput = remember(testSample, ruleType, pattern, replacement, wholeWord, caseSensitive, isEnabled) {
        if (testSample.isBlank() || pattern.isBlank() || validation !is RuleValidationResult.Valid) {
            testSample
        } else {
            val previewRule = initialRule.copy(
                ruleType = ruleType,
                pattern = pattern,
                replacement = replacement,
                isRegex = ruleType == TtsRuleType.SKIP_REGEX,
                wholeWord = if (ruleType == TtsRuleType.SKIP_REGEX) false else wholeWord,
                caseSensitive = caseSensitive,
                isEnabled = isEnabled
            )
            TtsTextProcessor.test(testSample, listOf(previewRule))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isNew) "Add Speech Rule" else "Edit Speech Rule")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 3-way Rule Type Selector: Skip, Regex Skip, Pronunciation
                Text(
                    text = "Rule Type",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = ruleType == TtsRuleType.SKIP,
                        onClick = { ruleType = TtsRuleType.SKIP },
                        label = { Text("Skip", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = ruleType == TtsRuleType.SKIP_REGEX,
                        onClick = { ruleType = TtsRuleType.SKIP_REGEX },
                        label = { Text("Regex Skip", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = ruleType == TtsRuleType.REPLACE,
                        onClick = { ruleType = TtsRuleType.REPLACE },
                        label = { Text("Pronunciation", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1.2f)
                    )
                }

                // Type-specific pattern input
                when (ruleType) {
                    TtsRuleType.SKIP -> {
                        OutlinedTextField(
                            value = pattern,
                            onValueChange = { pattern = it },
                            label = { Text("Word or Phrase to Skip") },
                            placeholder = { Text("e.g. Tomato") },
                            isError = validation is RuleValidationResult.Invalid && pattern.isNotBlank(),
                            supportingText = { Text("Matched text will be omitted from TTS speech.") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    TtsRuleType.SKIP_REGEX -> {
                        OutlinedTextField(
                            value = pattern,
                            onValueChange = { pattern = it },
                            label = { Text("Regular Expression Pattern") },
                            placeholder = { Text("e.g. \\[.*?\\]") },
                            isError = validation is RuleValidationResult.Invalid && pattern.isNotBlank(),
                            supportingText = {
                                if (validation is RuleValidationResult.Invalid && pattern.isNotBlank()) {
                                    Text(
                                        text = (validation as RuleValidationResult.Invalid).reason,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text("Text matching this regex will be omitted from TTS speech.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    TtsRuleType.REPLACE -> {
                        OutlinedTextField(
                            value = pattern,
                            onValueChange = { pattern = it },
                            label = { Text("Original Text in Novel") },
                            placeholder = { Text("e.g. Tom") },
                            supportingText = { Text("Text appearing in the reader.") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = replacement,
                            onValueChange = { replacement = it },
                            label = { Text("Spoken Replacement") },
                            placeholder = { Text("e.g. Jack") },
                            supportingText = { Text("What Android TTS will speak instead.") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                // Matching Mode Options
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (ruleType != TtsRuleType.SKIP_REGEX) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Whole Word Only", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = wholeWord, onCheckedChange = { wholeWord = it })
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Case Sensitive", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                    }
                }

                HorizontalDivider()

                // Interactive Test Sandbox
                Text(
                    text = "Live Test Preview",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = testSample,
                    onValueChange = { testSample = it },
                    label = { Text("Test Input") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "TTS will speak:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = testOutput.ifBlank { "(empty - paragraph skipped)" },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (testOutput.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
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
                enabled = pattern.isNotBlank() && validation is RuleValidationResult.Valid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

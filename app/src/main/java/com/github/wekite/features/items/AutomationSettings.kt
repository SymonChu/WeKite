package com.github.wekite.features.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.wekite.features.api.core.models.IWeContact
import com.github.wekite.ui.content.BaseContactSelector
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.MINUTES_PER_DAY
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.WeTimeOfDayField
import com.github.wekite.ui.content.formatMinuteOfDay
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.serialization.DefaultJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
internal data class AutomationToggleRule(val enabled: Boolean = false)

@Serializable
internal data class AutomationTimeRangeRule(
    val enabled: Boolean = false,
    val startMinute: Int = 0,
    val endMinute: Int = 0
) {
    fun matches(now: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled) return true
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) current in start until end else current !in end..<start
    }
}

@Serializable
internal enum class AutomationKeywordMode {
    STRING_LIST,
    REGEX
}

@Serializable
internal data class AutomationKeywordRule(
    val enabled: Boolean = false,
    val mode: AutomationKeywordMode = AutomationKeywordMode.STRING_LIST,
    val exclude: Boolean = false,
    val strings: List<String> = emptyList(),
    val regex: String = ""
) {
    fun matches(text: String): Boolean {
        if (!enabled) return true
        val matched = when (mode) {
            AutomationKeywordMode.STRING_LIST -> strings
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .any { text.contains(it) }

            AutomationKeywordMode.REGEX -> runCatching {
                Regex(regex).containsMatchIn(text)
            }.getOrDefault(false)
        }
        // exclude=true: 命中关键词的内容被跳过; exclude=false: 仅命中关键词的内容被处理
        return if (exclude) !matched else matched
    }

    fun validationError(label: String): String? {
        if (!enabled) return null
        return when (mode) {
            AutomationKeywordMode.STRING_LIST ->
                if (strings.none(String::isNotBlank)) "${label}字符串列表不能为空" else null

            AutomationKeywordMode.REGEX -> when {
                regex.isBlank() -> "${label}正则表达式不能为空"
                runCatching { Regex(regex) }.isFailure -> "${label}正则表达式格式不正确"
                else -> null
            }
        }
    }
}

internal class AtomicJsonConfigStore<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val tag: String,
    private val initialValue: () -> T
) {
    @Volatile
    private var cached: T? = null

    fun get(): T {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: read().also { cached = it }
        }
    }

    fun update(transform: (T) -> T): T = synchronized(this) {
        val updated = transform(get())
        write(updated)
        cached = updated
        updated
    }

    private fun read(): T {
        if (!file.exists()) {
            return initialValue().also(::write)
        }
        return runCatching {
            DefaultJson.decodeFromString(serializer, file.readText())
        }.onFailure {
            WeLogger.e(tag, "failed to read $file", it)
        }.getOrElse { initialValue() }
    }

    private fun write(value: T) {
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            temporary.writeText(DefaultJson.encodeToString(serializer, value))
            runCatching {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.getOrElse {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            WeLogger.e(tag, "failed to save $file", it)
        }
    }
}

@Composable
internal fun AutomationContactSettingsSelector(
    title: String,
    contacts: List<IWeContact>,
    selectionKey: Any,
    subtitle: (IWeContact) -> String,
    isConfigured: (IWeContact) -> Boolean,
    onDismiss: () -> Unit,
    onOpen: (IWeContact) -> Unit,
    enableMultiSelect: Boolean = false,
    onBatchSkip: (Set<String>) -> Unit = {},
    onBatchReset: (Set<String>) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var multiSelect by remember { mutableStateOf(false) }
    var selectedWxIds by remember { mutableStateOf(emptySet<String>()) }
    val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }
    val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { first, second ->
                    chinaCollator.compare(first.displayName, second.displayName)
                }
        )
    }

    fun toggleMultiSelect() {
        multiSelect = !multiSelect
        if (!multiSelect) selectedWxIds = emptySet()
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        allContacts = contacts,
        confirmButtonText = "",
        confirmButtonEnabled = false,
        showConfirmButton = multiSelect,
        dismissButtonText = "关闭",
        onDismiss = onDismiss,
        onConfirm = {},
        selectionKey = if (multiSelect) selectedWxIds else selectionKey,
        isSelected = if (multiSelect) {
            { it.wxId in selectedWxIds }
        } else {
            isConfigured
        },
        subtitleProvider = subtitle,
        trailingControl = if (multiSelect) null else { contact ->
            TextButton(onClick = { onOpen(contact) }) { Text("设置") }
        },
        leadingControl = if (multiSelect) { contact ->
            Checkbox(
                checked = contact.wxId in selectedWxIds,
                onCheckedChange = null
            )
        } else null,
        onItemClick = if (multiSelect) { contact ->
            selectedWxIds = if (contact.wxId in selectedWxIds) {
                selectedWxIds - contact.wxId
            } else {
                selectedWxIds + contact.wxId
            }
        } else {
            onOpen
        },
        onSelectAll = if (multiSelect) { displayed ->
            selectedWxIds = selectedWxIds + displayed.map { it.wxId }
        } else null,
        onDeselectAll = if (multiSelect) { displayed ->
            selectedWxIds = selectedWxIds - displayed.map { it.wxId }.toSet()
        } else null,
        onInvertSelection = if (multiSelect) { displayed ->
            val displayedWxIds = displayed.map { it.wxId }.toSet()
            val newSelection = selectedWxIds.toMutableSet()
            for (wxId in displayedWxIds) {
                if (wxId in newSelection) newSelection.remove(wxId) else newSelection.add(wxId)
            }
            selectedWxIds = newSelection
        } else null,
        extraTitleAction = if (enableMultiSelect) {
            {
                TextButton(onClick = ::toggleMultiSelect) {
                    Text(if (multiSelect) "完成" else "多选")
                }
            }
        } else null,
        confirmButtonOverride = if (multiSelect) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        enabled = selectedWxIds.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onBatchSkip(selectedWxIds)
                            selectedWxIds = emptySet()
                        }
                    ) { Text("设为跳过") }
                    Button(
                        enabled = selectedWxIds.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onBatchReset(selectedWxIds)
                            selectedWxIds = emptySet()
                        }
                    ) { Text("恢复全局") }
                }
            }
        } else null
    )
}

@Composable
internal fun AutomationRuleHeader(
    title: String,
    summary: String,
    enabled: Boolean,
    isOverridden: Boolean? = null,
    parentLabel: String = "",
    onActivate: () -> Unit = {},
    onReset: () -> Unit = {},
    onEnabledChange: (Boolean) -> Unit
) {
    val editable = isOverridden != false
    val effectiveSummary = if (isOverridden == false) "跟随$parentLabel: $summary" else summary
    ListItem(
        colors = dialogListItemColors(),
        modifier = Modifier.clickable {
            if (editable) onEnabledChange(!enabled) else onActivate()
        },
        leadingContent = {
            Switch(
                checked = enabled,
                enabled = editable,
                onCheckedChange = if (editable) onEnabledChange else null
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(effectiveSummary) },
        trailingContent = if (isOverridden != null) {
            {
                TextButton(enabled = isOverridden, onClick = onReset) {
                    Text("重置")
                }
            }
        } else null
    )
}

@Composable
internal fun AutomationTimeRangeControls(
    rule: AutomationTimeRangeRule,
    editable: Boolean,
    onChange: (AutomationTimeRangeRule) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WeTimeOfDayField(
            modifier = Modifier.weight(1f),
            label = "开始",
            minuteOfDay = rule.startMinute,
            enabled = editable,
            onMinuteChange = { onChange(rule.copy(startMinute = it)) }
        )
        WeTimeOfDayField(
            modifier = Modifier.weight(1f),
            label = "结束",
            minuteOfDay = rule.endMinute,
            enabled = editable,
            onMinuteChange = { onChange(rule.copy(endMinute = it)) }
        )
    }
}

@Composable
internal fun AutomationKeywordControls(
    rule: AutomationKeywordRule,
    editable: Boolean,
    onChange: (AutomationKeywordRule) -> Unit
) {
    var pendingKeyword by remember { mutableStateOf("") }
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        listOf(false to "包含匹配", true to "排除匹配").forEachIndexed { index, (exclude, label) ->
            SegmentedButton(
                selected = rule.exclude == exclude,
                enabled = editable,
                onClick = { onChange(rule.copy(exclude = exclude)) },
                shape = SegmentedButtonDefaults.itemShape(index, 2)
            ) {
                Text(label)
            }
        }
    }
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        AutomationKeywordMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = rule.mode == mode,
                enabled = editable,
                onClick = { onChange(rule.copy(mode = mode)) },
                shape = SegmentedButtonDefaults.itemShape(index, AutomationKeywordMode.entries.size)
            ) {
                Text(if (mode == AutomationKeywordMode.STRING_LIST) "字符串列表" else "正则表达式")
            }
        }
    }
    if (rule.mode == AutomationKeywordMode.STRING_LIST) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = pendingKeyword,
                enabled = editable,
                onValueChange = { pendingKeyword = it },
                label = { Text("新关键词") },
                singleLine = true
            )
            Button(
                enabled = editable && pendingKeyword.trim().isNotEmpty(),
                onClick = {
                    val keyword = pendingKeyword.trim()
                    if (keyword !in rule.strings) onChange(rule.copy(strings = rule.strings + keyword))
                    pendingKeyword = ""
                }
            ) { Text("添加") }
        }
        rule.strings.forEach { keyword ->
            ListItem(
                colors = dialogListItemColors(),
                headlineContent = { Text(keyword) },
                trailingContent = {
                    TextButton(
                        enabled = editable,
                        onClick = { onChange(rule.copy(strings = rule.strings - keyword)) }
                    ) { Text("删除") }
                }
            )
        }
    } else {
        val regexError = rule.regex.takeIf(String::isNotBlank)?.let {
            runCatching { Regex(it) }.exceptionOrNull()?.message
        }
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            value = rule.regex,
            enabled = editable,
            onValueChange = { onChange(rule.copy(regex = it)) },
            label = { Text("Regex") },
            supportingText = regexError?.let { error -> { Text(error) } },
            isError = regexError != null,
            singleLine = true
        )
    }
}

@Composable
internal fun AutomationSettingsError(error: String?) {
    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun AutomationScrollableColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

internal fun formatAutomationMinute(value: Int): String = formatMinuteOfDay(value)

internal fun automationKeywordSummary(rule: AutomationKeywordRule, unrestrictedText: String): String {
    if (!rule.enabled) return unrestrictedText
    val scope = if (rule.exclude) "跳过命中" else "仅处理命中"
    return when (rule.mode) {
        AutomationKeywordMode.STRING_LIST -> "${scope}字符串列表的内容 (${rule.strings.size})"
        AutomationKeywordMode.REGEX -> if (rule.regex.isBlank()) "尚未填写正则表达式" else "${scope}正则的内容"
    }
}


package org.openlist.mobile.data.preferences

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.openlist.mobile.core.model.FileVisibilityAction
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.FileVisibilityTarget

/** Malformed individual rules do not prevent the rest of the local settings from loading. */
internal object FileVisibilityPreferencesCodec {
    fun encode(rules: List<FileVisibilityRule>): String {
        require(rules.map { it.id }.distinct().size == rules.size) { "筛选规则标识重复" }
        return JsonArray().apply {
            rules.forEach { rule ->
                add(JsonObject().apply {
                    addProperty("id", rule.id)
                    addProperty("pattern", rule.pattern)
                    addProperty("action", rule.action.name)
                    addProperty("target", rule.target.name)
                })
            }
        }.toString()
    }

    fun decode(encoded: String?): List<FileVisibilityRule> {
        if (encoded.isNullOrBlank()) return emptyList()
        val array = runCatching { JsonParser.parseString(encoded).asJsonArray }.getOrNull()
            ?: return emptyList()
        val seen = mutableSetOf<String>()
        return array.mapNotNull { element ->
            val rule = runCatching {
                val item = element.asJsonObject
                FileVisibilityRule(
                    id = item.get("id").asString,
                    pattern = item.get("pattern").asString,
                    action = FileVisibilityAction.valueOf(item.get("action").asString),
                    target = FileVisibilityTarget.valueOf(item.get("target").asString),
                )
            }.getOrNull()
            rule?.takeIf { seen.add(it.id) }
        }
    }
}

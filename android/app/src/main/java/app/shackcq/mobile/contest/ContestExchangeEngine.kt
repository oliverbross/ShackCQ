package app.shackcq.mobile.contest

import java.util.Locale

class ContestExchangeEngine {
    fun parse(definition: ContestDefinition, text: String): Map<ContestExchangeField, String> {
        val tokens = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) return emptyMap()
        val fields = definition.receivedExchange.filterNot { it == ContestExchangeField.RST }
        if (fields.isEmpty()) return emptyMap()
        if (fields.size == 1) return mapOf(fields.single() to normalize(fields.single(), tokens.joinToString(" ")))
        val result = linkedMapOf<ContestExchangeField, String>()
        fields.forEachIndexed { index, field ->
            val value = if (index == fields.lastIndex) tokens.drop(index).joinToString(" ") else tokens.getOrNull(index).orEmpty()
            if (value.isNotBlank()) result[field] = normalize(field, value)
        }
        return result
    }

    fun validate(definition: ContestDefinition, values: Map<ContestExchangeField, String>): List<ContestValidationIssue> = buildList {
        val alternatives = when (definition.family) {
            ContestRuleFamily.ARRL_DX -> setOf(ContestExchangeField.POWER, ContestExchangeField.STATE_PROVINCE)
            ContestRuleFamily.IARU_HF -> setOf(ContestExchangeField.ITU_ZONE, ContestExchangeField.HQ_ABBREVIATION, ContestExchangeField.MEMBER_SOCIETY)
            ContestRuleFamily.CQ_160 -> setOf(ContestExchangeField.STATE_PROVINCE, ContestExchangeField.CQ_ZONE)
            else -> emptySet()
        }
        if (alternatives.isNotEmpty() && alternatives.none { values[it].orEmpty().isNotBlank() })
            add(ContestValidationIssue(ContestTruth.INCOMPLETE, null, "One of ${alternatives.joinToString()} is required"))
        definition.receivedExchange.filterNot { it == ContestExchangeField.RST || it in alternatives && values[it].orEmpty().isBlank() }.forEach { field ->
            val value = values[field].orEmpty()
            if (value.isBlank()) add(ContestValidationIssue(ContestTruth.INCOMPLETE, field, "${field.name} is required"))
            else when (field) {
                ContestExchangeField.SERIAL -> if (value.toIntOrNull()?.let { it > 0 } != true) add(ContestValidationIssue(ContestTruth.INVALID, field, "Serial must be a positive number"))
                ContestExchangeField.CQ_ZONE -> if (value.toIntOrNull() !in 1..40) add(ContestValidationIssue(ContestTruth.INVALID, field, "CQ zone must be 1–40"))
                ContestExchangeField.ITU_ZONE -> if (value.toIntOrNull() !in 1..90) add(ContestValidationIssue(ContestTruth.INVALID, field, "ITU zone must be 1–90"))
                ContestExchangeField.POWER -> if (!Regex("^(QRP|[0-9]{1,4})(W)?$").matches(value)) add(ContestValidationIssue(ContestTruth.INVALID, field, "Power must be watts or QRP"))
                ContestExchangeField.CLASS -> if (!Regex("^[1-9][0-9]?[A-F]$").matches(value)) add(ContestValidationIssue(ContestTruth.INVALID, field, "Field Day class must be 1–99 followed by A–F"))
                ContestExchangeField.STATE_PROVINCE, ContestExchangeField.ARRL_SECTION,
                ContestExchangeField.HQ_ABBREVIATION, ContestExchangeField.MEMBER_SOCIETY ->
                    if (!Regex("^[A-Z0-9]{2,8}$").matches(value)) add(ContestValidationIssue(ContestTruth.INVALID, field, "Unsupported exchange abbreviation"))
                else -> Unit
            }
        }
    }

    fun expectedHint(definition: ContestDefinition, entity: ContestEntityInfo): Pair<String, String> = when (definition.family) {
        ContestRuleFamily.CQ_WW -> entity.cqZone.takeIf(String::isNotBlank)?.let { "RST + CQ zone $it" to "CTY snapshot" }
        ContestRuleFamily.ARRL_DX -> if (entity.isWve == true) entity.stateProvince.takeIf(String::isNotBlank)?.let { "RST + $it" to "CTY/state snapshot" } else "RST + power" to "Official entrant-side rule"
        ContestRuleFamily.IARU_HF -> entity.hqSociety.takeIf(String::isNotBlank)?.let { "RST + $it" to "IARU HQ snapshot" }
            ?: entity.ituZone.takeIf(String::isNotBlank)?.let { "RST + ITU zone $it" to "CTY snapshot" }
        ContestRuleFamily.ARRL_FIELD_DAY -> entity.arrlSection.takeIf(String::isNotBlank)?.let { "Class + $it" to "ARRL section snapshot" }
        ContestRuleFamily.CQ_160 -> entity.stateProvince.takeIf(String::isNotBlank)?.let { "RST + $it" to "CTY/state snapshot" } ?: ("RST + CQ zone" to "Official rule")
        else -> if (definition.serialRequired) "RST + serial" to "Official rule" else null
    } ?: ("Exchange unknown — copy what is sent" to "No authoritative lookup value")

    private fun normalize(field: ContestExchangeField, value: String): String = when (field) {
        ContestExchangeField.SERIAL -> value.filter(Char::isDigit).trimStart('0').ifBlank { "0" }
        ContestExchangeField.CQ_ZONE, ContestExchangeField.ITU_ZONE -> value.filter(Char::isDigit)
        else -> value.trim().uppercase(Locale.US).replace(Regex("\\s+"), " ")
    }
}

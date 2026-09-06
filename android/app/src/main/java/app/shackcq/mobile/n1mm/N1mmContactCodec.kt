package app.shackcq.mobile.n1mm

object N1mmContactCodec {
    const val MAX_FIELD_CHARS = 4096
    fun decode(fields: List<String>): N1mmContact {
        require(fields.size >= N1mmContact.FIELD_NAMES.size) { "N1MM contact requires 43 fields" }
        require(fields.all { it.length <= MAX_FIELD_CHARS })
        fields[2].takeIf(String::isNotBlank)?.let { require(it.toLongOrNull() != null) { "Malformed frequency" } }
        fields[19].takeIf(String::isNotBlank)?.let { require(it.toIntOrNull() != null) { "Malformed points" } }
        fields[39].takeIf(String::isNotBlank)?.let { require(it in setOf("0", "1")) { "Malformed IsOriginal" } }
        fields[41].takeIf(String::isNotBlank)?.let { require(Regex("^[0-9A-Fa-f]{32}$").matches(it)) { "Malformed contact ID" } }
        return N1mmContact(fields.toList())
    }
    fun encode(contact: N1mmContact): List<String> = contact.fields.also { require(it.size >= 43 && it.all { field -> field.length <= MAX_FIELD_CHARS }) }
}

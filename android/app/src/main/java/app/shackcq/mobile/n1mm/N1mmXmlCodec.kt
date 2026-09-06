package app.shackcq.mobile.n1mm

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class N1mmXmlMessage(val root: String, val fields: Map<String, String>)

object N1mmXmlCodec {
    const val MAX_XML_BYTES = 64 * 1024
    val outboundRoots = setOf("RadioInfo", "AppInfo", "contactinfo", "contactreplace", "contactdelete", "lookupinfo", "scoreinfo", "score", "spot")
    val codecOnlyControlRoots = setOf("CWControlString", "CWSendStr", "SetBufPTT", "SendCW", "RoverQTH", "radio_setfrequency", "RadioCmd", "RCmd", "Spectrum", "SetWPM", "Tune", "TuneStop", "Reset", "PortOpen", "RTSEnable", "DTREnable", "WinkeyPutChar")

    fun decode(payload: ByteArray): N1mmXmlMessage {
        require(payload.size in 1..MAX_XML_BYTES)
        val prefix = payload.toString(Charsets.UTF_8).take(1024).uppercase()
        require("<!DOCTYPE" !in prefix && "<!ENTITY" !in prefix) { "DTD and entities are prohibited" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false; isXIncludeAware = false; setExpandEntityReferences(false)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(payload))
        val root = document.documentElement ?: error("Missing XML root")
        require(root.tagName in outboundRoots || root.tagName in codecOnlyControlRoots || root.tagName == "RadioInfo") { "Unknown N1MM XML root" }
        val fields = linkedMapOf<String, String>()
        for (i in 0 until root.childNodes.length) (root.childNodes.item(i) as? Element)?.let { fields[it.tagName] = it.textContent.take(4096) }
        return N1mmXmlMessage(root.tagName, fields)
    }

    fun encode(message: N1mmXmlMessage): ByteArray {
        require(message.root in outboundRoots)
        fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        val bytes = buildString { append('<').append(message.root).append('>'); message.fields.forEach { (key,value) -> require(Regex("^[A-Za-z_][A-Za-z0-9_.-]*$").matches(key)); append('<').append(key).append('>').append(esc(value.take(4096))).append("</").append(key).append('>') }; append("</").append(message.root).append('>') }.toByteArray()
        require(bytes.size <= MAX_XML_BYTES); return bytes
    }
}

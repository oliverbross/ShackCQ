package app.shackcq.mobile.keyer

import app.shackcq.mobile.CwMacroContext
import app.shackcq.mobile.resolveCwMacroTemplate

data class KeyerTemplateResolution(val text: String = "", val error: KeyerFailureReason? = null, val detail: String = "")

object KeyerTemplateResolver {
    fun resolve(template: String, context: KeyerContextSnapshot): KeyerTemplateResolution {
        val result = resolveCwMacroTemplate(template, CwMacroContext(
            myCall = context.myCall, call = context.call, rst = context.rst, rstSent = context.rstSent,
            rstRecv = context.rstRecv, grid = context.grid, serial = context.serial, exchange = context.exchange,
            reference = context.reference, mode = context.mode.name, band = context.band,
        ))
        return if (result.error == null) KeyerTemplateResolution(text = result.text)
        else KeyerTemplateResolution(error = if (listOf("limit", "unsupported", "exceeds").any(result.error::contains)) KeyerFailureReason.BackendCapacityExceeded
            else KeyerFailureReason.CwTextInvalid, detail = result.error)
    }
}

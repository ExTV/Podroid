package com.excp.podroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.excp.podroid.R
import com.excp.podroid.engine.EngineSelection

/** Resolved strings [backendLabel] needs, so the mapping itself stays unit-testable. */
internal data class BackendLabelStrings(
    val auto: String,
    val avf: String,
    val qemu: String,
    val uml: String,
    val fallbackSuffix: String,
    val fallbackSuffixUml: String,
)

internal fun backendLabel(
    selection: EngineSelection,
    activeId: String,
    s: BackendLabelStrings,
): String {
    val activeLabel = when (activeId) {
        "avf" -> s.avf
        "uml" -> s.uml
        else -> s.qemu
    }
    return when (selection) {
        EngineSelection.AUTO -> "${s.auto} ($activeId)"
        // Forced AVF but the actual pick fell back to QEMU (device can't run AVF) -
        // say so instead of claiming AVF is active.
        EngineSelection.AVF -> if (activeId == "avf") activeLabel else activeLabel + s.fallbackSuffix
        EngineSelection.QEMU -> activeLabel
        // Forced UML but the pick fell back (probe failed or demoted): say so.
        EngineSelection.UML -> if (activeId == "uml") activeLabel else activeLabel + s.fallbackSuffixUml
    }
}

@Composable
fun backendLabel(selection: EngineSelection, activeId: String): String = backendLabel(
    selection,
    activeId,
    BackendLabelStrings(
        auto = stringResource(R.string.auto),
        avf = stringResource(R.string.avf_kvm),
        qemu = stringResource(R.string.qemu_tcg),
        uml = stringResource(R.string.uml_status_label),
        fallbackSuffix = stringResource(R.string.backend_fallback_suffix),
        fallbackSuffixUml = stringResource(R.string.backend_fallback_suffix_uml),
    ),
)

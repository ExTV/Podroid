package com.excp.podroid.ui.components

import com.excp.podroid.engine.EngineSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendLabelTest {
    private val s = BackendLabelStrings(
        auto = "Auto",
        avf = "AVF (KVM)",
        qemu = "QEMU (TCG)",
        uml = "UML (user-mode)",
        fallbackSuffix = " - AVF unavailable, running QEMU",
        fallbackSuffixUml = " - UML unavailable",
    )

    private fun label(sel: EngineSelection, id: String) = backendLabel(sel, id, s)

    @Test fun autoShowsActiveId() {
        assertEquals("Auto (qemu)", label(EngineSelection.AUTO, "qemu"))
        assertEquals("Auto (avf)", label(EngineSelection.AUTO, "avf"))
    }

    @Test fun forcedBackendsMatchingActive() {
        assertEquals("QEMU (TCG)", label(EngineSelection.QEMU, "qemu"))
        assertEquals("AVF (KVM)", label(EngineSelection.AVF, "avf"))
        assertEquals("UML (user-mode)", label(EngineSelection.UML, "uml"))
    }

    @Test fun avfFallsBackToQemu() {
        assertEquals("QEMU (TCG) - AVF unavailable, running QEMU", label(EngineSelection.AVF, "qemu"))
    }

    @Test fun umlFallsBack() {
        assertEquals("QEMU (TCG) - UML unavailable", label(EngineSelection.UML, "qemu"))
        assertEquals("AVF (KVM) - UML unavailable", label(EngineSelection.UML, "avf"))
    }

    @Test fun forcedQemuIgnoresOtherActive() {
        assertEquals("UML (user-mode)", label(EngineSelection.QEMU, "uml"))
    }
}

package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The badge on a face is chosen by how the name ends, and by nothing else. */
class RegistryOfTest {
    @Test fun aNameIsFiledUnderItsEnding() {
        assertEquals(".skr", registryOf("arpita.skr"))
        assertEquals(".sol", registryOf("Toly.SOL"))
        assertEquals(".air", registryOf(" fahad.air "))
    }

    @Test fun anythingElseHasNoRegistry() {
        assertNull(registryOf("Arpita"))
        assertNull(registryOf("4WZ9hk"))
        assertNull(registryOf(".skr"))      // an ending with no name in front of it
        assertNull(registryOf(null))
    }
}

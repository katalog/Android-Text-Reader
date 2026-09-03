package com.moonkata.textreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPairingPayloadTest {

    @Test
    fun `parses vscode_sync payload`() {
        val result = QrPairingPayload.parse("""{"type":"vscode_sync","secret":"abc123"}""")
        assertEquals(QrPairingPayload.VscodeSync(secret = "abc123"), result)
    }

    @Test
    fun `parses pc_sync payload including colon-heavy host and fingerprint`() {
        val raw = """{"type":"pc_sync","host":"192.168.0.12:58221","secret":"xyz","fingerprint":"AB:CD:EF:01"}"""
        val result = QrPairingPayload.parse(raw)
        assertEquals(
            QrPairingPayload.PcSync(host = "192.168.0.12:58221", secret = "xyz", fingerprint = "AB:CD:EF:01"),
            result,
        )
    }

    @Test
    fun `unknown type returns null`() {
        assertNull(QrPairingPayload.parse("""{"type":"something_else","secret":"abc"}"""))
    }

    @Test
    fun `missing required field returns null instead of throwing`() {
        assertNull(QrPairingPayload.parse("""{"type":"vscode_sync"}"""))
        assertNull(QrPairingPayload.parse("""{"type":"pc_sync","host":"1.2.3.4:1"}"""))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(QrPairingPayload.parse("not json at all"))
        assertNull(QrPairingPayload.parse(""))
    }

    @Test
    fun `missing type field returns null`() {
        assertNull(QrPairingPayload.parse("""{"secret":"abc123"}"""))
    }
}

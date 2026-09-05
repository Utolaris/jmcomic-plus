package com.par9uet.jm.network

import com.par9uet.jm.store.DohPreferences
import com.par9uet.jm.store.DohPreferencesEditor
import com.par9uet.jm.store.DohSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DohManagerTest {
    @Test
    fun `disabled DoH uses system resolver and publishes inactive status`() {
        val prefs = FakePreferences(DohSettingsState(enabled = false, autoStart = false))
        val manager = DohManager(prefs, FakeEditor(prefs))

        val addresses = manager.lookup("127.0.0.1")

        assertEquals(listOf("127.0.0.1"), addresses.map { it.hostAddress })
        assertFalse(manager.status.value.active)
    }

    @Test
    fun `auto start remains off until explicitly enabled`() = runTest {
        val prefs = FakePreferences(DohSettingsState(enabled = true, autoStart = false))
        val editor = FakeEditor(prefs)
        val manager = DohManager(prefs, editor)

        manager.init()
        assertFalse(manager.status.value.active)

        manager.setEnabled(true)
        assertEquals(true, editor.enabled)

        // Whether the TLS client can be initialized in this JVM, an enabled DoH session must
        // never fall through to Dns.SYSTEM when the resolver is unavailable.
        if (!manager.status.value.active) {
            assertThrows(UnknownHostException::class.java) { manager.lookup("example.com") }
        }
    }

    @Test
    fun `server selection and custom server validation update persisted settings`() {
        val prefs = FakePreferences(DohSettingsState(enabled = false))
        val editor = FakeEditor(prefs)
        val manager = DohManager(prefs, editor)

        manager.selectServer(DOH_SERVER_ALIDNS)
        assertEquals(DOH_SERVER_ALIDNS, editor.serverId)
        assertEquals("阿里 DNS", manager.selectedServer().name)

        manager.saveCustomServer("测试", "https://dns.example/dns-query")
        assertEquals(DOH_SERVER_CUSTOM, editor.serverId)
        assertEquals("测试", editor.customName)
        assertEquals("https://dns.example/dns-query", editor.customUrl)
    }

    private class FakePreferences(initial: DohSettingsState) : DohPreferences {
        override val doh = MutableStateFlow(initial)
    }

    private class FakeEditor(private val prefs: FakePreferences) : DohPreferencesEditor {
        var enabled = prefs.doh.value.enabled
        var serverId = prefs.doh.value.serverId
        var customName = prefs.doh.value.customServerName
        var customUrl = prefs.doh.value.customServerUrl

        override fun persistEnabled(enabled: Boolean) {
            this.enabled = enabled
            prefs.doh.value = prefs.doh.value.copy(enabled = enabled)
        }

        override fun persistAutoStart(enabled: Boolean) {
            prefs.doh.value = prefs.doh.value.copy(autoStart = enabled)
        }

        override fun persistServer(serverId: String) {
            this.serverId = serverId
            prefs.doh.value = prefs.doh.value.copy(serverId = serverId)
        }

        override fun persistCustomServer(name: String, url: String) {
            serverId = DOH_SERVER_CUSTOM
            customName = name
            customUrl = url
            prefs.doh.value = prefs.doh.value.copy(
                serverId = DOH_SERVER_CUSTOM,
                customServerName = name,
                customServerUrl = url,
            )
        }

        override fun persistUseDeviceCertificates(enabled: Boolean) {
            prefs.doh.value = prefs.doh.value.copy(useDeviceCertificates = enabled)
        }

        override fun persistPreferIpv6(enabled: Boolean) {
            prefs.doh.value = prefs.doh.value.copy(preferIpv6 = enabled)
        }
    }
}

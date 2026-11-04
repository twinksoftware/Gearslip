package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.notify.MessagingApp
import app.seb3thehacker.gearslip.ui.CarGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HiddenAppsTest {
    private val context = InMemoryContext()

    private fun app(name: String) = Entry(name, icon = null, messaging = MessagingApp(name, name))
    private fun id(name: String) = MESSAGING_PREFIX + name
    private fun labels(entries: List<Entry>) = entries.map { it.label }

    private val apps = listOf(app("signal"), app("telegram"), app("whatsapp"))

    @Before
    fun startWithNothingSaved() = CarSettings.init(context)

    @Test
    fun everyTileShowsUntilUnticked() {
        assertEquals(emptySet<String>(), CarSettings.hiddenApps.value)
        assertEquals(apps.map { it.label }, labels(launcherOrder(apps, false, emptyList(), CarSettings.hiddenApps.value)))
    }

    @Test
    fun anUntickedTileLeavesTheDrawer() {
        val shown = launcherOrder(apps, false, emptyList(), setOf(id("telegram")))
        assertEquals(listOf("signal", "whatsapp"), labels(shown))
    }

    @Test
    fun gearslipsOwnTilesStayWhenInstalledOnesAreHidden() {
        val all = BuiltInApps.everyEntry + apps
        val shown = launcherOrder(all, false, emptyList(), apps.mapNotNull { it.componentId }.toSet())
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { it.builtIn != null })
    }

    @Test
    fun aHiddenTileLeavesTheDriversOrderIntactForTheRest() {
        val custom = listOf(id("whatsapp"), id("telegram"), id("signal"))
        val shown = launcherOrder(apps, false, custom, setOf(id("telegram")))
        assertEquals(listOf("whatsapp", "signal"), labels(shown))
    }

    @Test
    fun aTileComesBackWhenTickedAgain() {
        CarSettings.setAppHidden(id("signal"), true)
        CarSettings.setAppHidden(id("telegram"), true)
        CarSettings.setAppHidden(id("signal"), false)
        assertEquals(setOf(id("telegram")), CarSettings.hiddenApps.value)
    }

    @Test
    fun theChoicesSurviveARestart() {
        CarSettings.setAppHidden(id("signal"), true)
        CarSettings.setAppHidden(id("whatsapp"), true)
        CarSettings.init(context)
        assertEquals(setOf(id("signal"), id("whatsapp")), CarSettings.hiddenApps.value)
    }

    @Test
    fun appsIsTheFirstCardOnThePhone() {
        assertEquals(CarGroup.APPS, CarGroup.entries.first())
        assertFalse(CarGroup.entries.drop(1).contains(CarGroup.APPS))
    }
}

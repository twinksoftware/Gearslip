package app.seb3thehacker.gearslip.car

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MapAppSizeTest {
    private val context = InMemoryContext()

    @Before
    fun startOnAutomatic() {
        CarSettings.init(context)
        CarSettings.setMapAppSize(CarSettings.MAP_SIZE_AUTO)
    }

    @Test
    fun automaticLeavesAnOrdinaryScreenAlone() {
        // 800x480 at 160 with the nav bar over it: the map is 424 tall.
        assertEquals(160, CarSettings.mapAppDensity(160, 424))
        assertEquals(240, CarSettings.mapAppDensity(240, 650))
    }

    @Test
    fun automaticShrinksAShortScreenUntilTheMapIs320dpTall() {
        // The SOOEMAKA's map, squashed and above the nav bar: 240 px at 170 dpi.
        assertEquals(120, CarSettings.mapAppDensity(170, 240))
    }

    @Test
    fun automaticStopsAtTheSmallestSize() {
        assertEquals(102, CarSettings.mapAppDensity(170, 100))
    }

    @Test
    fun aChosenSizeIsAPercentOfTheCarUi() {
        for (percent in CarSettings.MAP_SIZES) {
            CarSettings.setMapAppSize(percent)
            assertEquals(200 * percent / 100, CarSettings.mapAppDensity(200, 240))
        }
    }

    @Test
    fun theChoiceOutlivesARestart() {
        CarSettings.setMapAppSize(70)
        CarSettings.init(context)
        assertEquals(70, CarSettings.mapAppSize.value)
    }
}

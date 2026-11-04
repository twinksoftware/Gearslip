package app.seb3thehacker.gearslip.car

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CarDensityTest {
    private val context = InMemoryContext()

    private fun density() = CarEnvironment.frame.value.densityDpi

    @Before
    fun startFromTheCar() {
        CarSettings.init(context)
        CarEnvironment.setDisplay(800, 480, 160)
    }

    @Test
    fun fromCarKeepsEachCarsOwnDensity() {
        assertEquals(CarSettings.DENSITY_FROM_CAR, CarSettings.carDensity.value)
        for (dpi in listOf(120, 180, 213)) {
            CarEnvironment.setDisplay(800, 480, dpi)
            assertEquals(dpi, density())
        }
    }

    @Test
    fun aChosenDensityReachesEverythingThatReadsTheFrame() {
        for (dpi in CarSettings.DENSITIES) {
            CarSettings.setCarDensity(dpi)
            CarEnvironment.setDisplay(800, 480, 160)
            assertEquals(dpi, density())
        }
    }

    @Test
    fun aChosenDensityLeavesTheResolutionAlone() {
        CarSettings.setCarDensity(240)
        CarEnvironment.setDisplay(1280, 720, 160)
        assertEquals(CarEnvironment.Frame(1280, 720, 240), CarEnvironment.frame.value)
    }

    @Test
    fun aNewChoiceWaitsForTheNextSession() {
        CarSettings.setCarDensity(200)
        CarEnvironment.setDisplay(800, 480, 160)
        CarSettings.setCarDensity(240)
        assertEquals(200, density())
        CarEnvironment.setDisplay(800, 480, 160)
        assertEquals(240, density())
    }

    @Test
    fun thePreviewTakesUpANewChoiceWhenItOpens() {
        CarSettings.setCarDensity(200)
        CarEnvironment.applyDensity()
        assertEquals(200, density())
    }

    @Test
    fun goingBackToFromCarRestoresTheCarsOwnDensity() {
        CarSettings.setCarDensity(240)
        CarEnvironment.setDisplay(800, 480, 180)
        CarSettings.setCarDensity(CarSettings.DENSITY_FROM_CAR)
        CarEnvironment.applyDensity()
        assertEquals(180, density())
    }

    @Test
    fun aPreviewSizedForThePhoneFollowsTheChoiceToo() {
        assertEquals(213, CarSettings.effectiveDensity(213))
        CarSettings.setCarDensity(200)
        assertEquals(200, CarSettings.effectiveDensity(213))
    }

    @Test
    fun theChoiceSurvivesARestart() {
        CarSettings.setCarDensity(220)
        CarSettings.init(context)
        assertEquals(220, CarSettings.carDensity.value)
    }

    @Test
    fun aSavedDensityGearslipDoesNotOfferFallsBackToTheCar() {
        for (saved in listOf("201", "-1", "broken", "")) {
            context.stored["car_density"] = saved
            CarSettings.init(context)
            assertEquals(CarSettings.DENSITY_FROM_CAR, CarSettings.carDensity.value)
        }
    }
}

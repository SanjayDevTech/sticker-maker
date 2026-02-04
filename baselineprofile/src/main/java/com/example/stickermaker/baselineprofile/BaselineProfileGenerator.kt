package com.example.stickermaker.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods touched during a cold start (and the critical
 * user journeys we add later) so they ship pre-compiled.
 *
 * Generate with a device/emulator connected:
 *   ./gradlew :app:generateBaselineProfile
 * The result lands in app/src/release/generated/baselineProfiles/ — commit it.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        // Beyond startup, walk the screens so navigation transitions, the shared-element
        // morph, the bottom sheet and list scrolling/animations ship pre-compiled too.
        // Extend as real screens land — anything not exercised runs interpreted/JIT.
        device.findObject(By.text("Start something")).click()
        device.wait(Until.hasObject(By.text("A photo")), 3_000)
        device.pressBack()
        device.waitForIdle()

        device.findObject(By.desc("Settings")).click()
        device.wait(Until.hasObject(By.text("Design kit")), 3_000)
        device.findObject(By.text("Design kit")).click()
        device.wait(Until.hasObject(By.text("Buttons")), 3_000)
        val list = device.findObject(By.scrollable(true))
        list.setGestureMargin(device.displayWidth / 5)
        repeat(2) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        list.fling(Direction.UP)
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}

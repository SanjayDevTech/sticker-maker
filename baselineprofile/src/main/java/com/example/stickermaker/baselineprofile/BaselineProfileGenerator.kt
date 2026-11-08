package com.example.stickermaker.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
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
        device.tap("Start something")
        device.wait(Until.hasObject(By.text("Photos")), 3_000)
        device.pressBack()
        device.waitForIdle()

        device.findObject(By.desc("Settings")).click()
        device.wait(Until.hasObject(By.text("Design kit")), 3_000)
        // About → Licences, and one licence's sheet.
        device.waitForIdle()
        device.swipeList(down = true)
        device.wait(Until.hasObject(By.text("Licences")), 3_000)
        device.tap("Licences")
        device.wait(Until.hasObject(By.text("Read it")), 3_000)
        device.tap("Read it")
        device.wait(Until.hasObject(By.textContains("Permission is hereby granted")), 3_000)
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.text("Design kit")), 3_000)
        device.swipeList(down = false)
        device.tap("Design kit")
        device.wait(Until.hasObject(By.text("Buttons")), 3_000)
        device.waitForIdle()
        repeat(3) { device.swipeList(down = true) }
        repeat(3) { device.swipeList(down = false) }
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}

/**
 * Scrolls whatever list fills the screen with a raw swipe. UiObject2.scroll/fling hold the list's
 * accessibility node, which Compose replaces mid-scroll, so they fail with StaleObjectException.
 */
private fun UiDevice.swipeList(down: Boolean) {
    val x = displayWidth / 2
    val low = displayHeight * 4 / 5
    val high = displayHeight / 5
    if (down) swipe(x, low, x, high, 8) else swipe(x, high, x, low, 8)
    // A fling coasts on; a tap during it only stops the list. Let it come to rest.
    waitForIdle()
    SystemClock.sleep(SETTLE_MS)
}

/** Taps the element with [text] once it's on screen; fails clearly if it never shows. */
private fun UiDevice.tap(text: String) {
    val target = wait(Until.findObject(By.text(text)), 5_000) ?: error("\"$text\" never appeared")
    target.click()
    waitForIdle()
}

private const val SETTLE_MS = 600L

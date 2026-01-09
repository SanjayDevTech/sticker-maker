package com.example.stickermaker.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        // Add critical journeys here as screens are built (scroll the home list,
        // open the editor, …) — anything not exercised runs interpreted/JIT.
    }
}

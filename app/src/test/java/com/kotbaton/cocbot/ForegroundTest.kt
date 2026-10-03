package com.kotbaton.cocbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Какие окна меняют «приложение на экране»: от этого зависит, тапает бот или стоит на паузе. */
class ForegroundTest {

    private val own = "com.kotbaton.cocbot"

    @Test
    fun `игра и лаунчер меняют приложение на экране`() {
        assertFalse(BotAccessibilityService.isTransientWindow("com.supercell.clashofclans", "com.supercell.titan.GameApp", own))
        assertFalse(BotAccessibilityService.isTransientWindow("com.miui.home", "com.miui.home.launcher.Launcher", own))
    }

    @Test
    fun `экран самого бота ставит паузу`() {
        assertFalse(BotAccessibilityService.isTransientWindow(own, "com.kotbaton.cocbot.MainActivity", own))
    }

    @Test
    fun `плашки бота поверх игры, шторка и клавиатура паузу не ставят`() {
        assertTrue(BotAccessibilityService.isTransientWindow(own, "android.widget.TextView", own))
        assertTrue(BotAccessibilityService.isTransientWindow(own, "com.kotbaton.cocbot.CalibrationView", own))
        assertTrue(BotAccessibilityService.isTransientWindow("com.android.systemui", "com.android.systemui.shade.NotificationShadeWindowView", own))
        assertTrue(BotAccessibilityService.isTransientWindow("com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow", own))
        assertTrue(BotAccessibilityService.isTransientWindow("com.supercell.clashofclans", "android.widget.Toast\$TN", own))
        assertTrue(BotAccessibilityService.isTransientWindow("com.supercell.clashofclans", null, own))
    }
}

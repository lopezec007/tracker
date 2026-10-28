package io.github.lopezec007.tracker.osmand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityAppearanceTest {
    @Test
    fun validationFollowsTheServersRules() {
        assertTrue(EntityAppearance.isValidIcon(""))
        assertTrue(EntityAppearance.isValidIcon("ic_action_car_dark"))
        assertTrue(EntityAppearance.isValidIcon("mx_shop_car"))
        assertFalse(EntityAppearance.isValidIcon("Ic_Action"))
        assertFalse(EntityAppearance.isValidIcon("ic-action"))
        assertFalse(EntityAppearance.isValidIcon("a".repeat(65)))
        assertTrue(EntityAppearance.isValidColor(""))
        assertTrue(EntityAppearance.isValidColor("#1E88E5"))
        assertTrue(EntityAppearance.isValidColor("#abcdef"))
        assertFalse(EntityAppearance.isValidColor("1E88E5"))
        assertFalse(EntityAppearance.isValidColor("#1E88E"))
        assertFalse(EntityAppearance.isValidColor("#1E88E5FF"))
        assertFalse(EntityAppearance.isValidColor("red"))
    }

    @Test
    fun coloursParseToOpaqueArgb() {
        assertEquals(0xFF1E88E5.toInt(), EntityAppearance.parseColor("#1E88E5"))
        assertEquals(0xFF1E88E5.toInt(), EntityAppearance.parseColor("#1e88e5"))
        assertNull(EntityAppearance.parseColor(""))
        assertNull(EntityAppearance.parseColor("blue"))
        assertEquals("#1e88e5", EntityAppearance.gpxColor("#1E88E5"))
        assertNull(EntityAppearance.gpxColor(""))
    }

    @Test
    fun theCuratedListsAreValidAndStartWithTheDefault() {
        assertEquals("", EntityAppearance.ICONS.first().osmandName)
        assertEquals("", EntityAppearance.COLORS.first().hex)
        assertTrue(EntityAppearance.ICONS.all { EntityAppearance.isValidIcon(it.osmandName) })
        assertTrue(EntityAppearance.COLORS.all { EntityAppearance.isValidColor(it.hex) })
        assertEquals(EntityAppearance.ICONS.size, EntityAppearance.ICONS.map { it.osmandName }.toSet().size)
        assertTrue(EntityAppearance.ICONS.size >= 16)
        assertTrue(EntityAppearance.COLORS.size >= 11)
        assertEquals("Car", EntityAppearance.iconLabel("ic_action_car_dark"))
        assertEquals("mx_shop_car", EntityAppearance.iconLabel("mx_shop_car"))
    }
}

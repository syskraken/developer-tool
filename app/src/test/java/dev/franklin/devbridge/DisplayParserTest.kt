package dev.franklin.devbridge

import dev.franklin.devbridge.analysis.DisplayParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayParserTest {

    private val surfaceFlinger = """
        Display 4630946213990106498 (HWC display 0): port=0 pnpId=SAM displayName="Main panel"
        Display 4630946213990106499 (HWC display 1): port=1 pnpId=SAM displayName="Cover panel"
    """.trimIndent()

    private val displayManager = """
        Logical Displays: size=2
          Display 0:
            mDisplayId=0
            mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", displayGroupId 0, appWidth 1080, appHeight 2640, real 1080 x 2640, largest app 1080 x 2640, uniqueId "local:4630946213990106498", rotation 0}
            mOverrideDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", displayGroupId 0, real 1080 x 2640, uniqueId "local:4630946213990106498"}
          Display 1:
            mDisplayId=1
            mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 1", displayGroupId 0, appWidth 748, appHeight 720, real 748 x 720, largest app 748 x 720, uniqueId "local:4630946213990106499", rotation 0}
          Display 7:
            mBaseDisplayInfo=DisplayInfo{"Screenshot", displayId 7, real 100 x 100, uniqueId "virtual:com.example,1000,Screenshot,0"}
    """.trimIndent()

    @Test
    fun readsPhysicalIdsAndNames() {
        val p = DisplayParser.parsePhysical(surfaceFlinger)
        assertEquals(listOf("4630946213990106498" to "Main panel", "4630946213990106499" to "Cover panel"), p)
    }

    @Test
    fun joinsLogicalAndPhysicalAndDropsVirtualDisplays() {
        val c = DisplayParser.choices(displayManager, surfaceFlinger)
        assertEquals(2, c.size)
        assertEquals(0, c[0].logicalId)
        assertNull("the main screen is captured with no id", c[0].physicalId)
        assertEquals(1, c[1].logicalId)
        assertEquals("4630946213990106499", c[1].physicalId)
        assertEquals(748, c[1].width)
        assertEquals(720, c[1].height)
        assertEquals("Built-in Screen", c[1].name)
        assertTrue(c[1].label().contains("display 1"))
    }

    @Test
    fun fallsBackToOrderedGuessWhenTheDisplayManagerGivesNothing() {
        val c = DisplayParser.choices("", surfaceFlinger)
        assertEquals(2, c.size)
        assertEquals(1, c[1].logicalId)
        assertEquals("4630946213990106499", c[1].physicalId)
        assertTrue(c[1].guessed)
        assertTrue(c[1].label().contains("guessed"))
    }

    @Test
    fun emptyOutputGivesNoChoices() {
        assertTrue(DisplayParser.choices("", "").isEmpty())
    }
}

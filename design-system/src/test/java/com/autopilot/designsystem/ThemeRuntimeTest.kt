package com.autopilot.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRuntimeTest {

  @Test
  fun `schema validator accepts valid W3C DTCG document and resolves aliases`() {
    val json =
      """
      {
        "schemaVersion": "1.0.0",
        "id": "custom_test",
        "name": "Custom Test",
        "appearance": "dark",
        "extendsThemeId": "cockpit_dark",
        "tokens": {
          "primitive.sky": { "${'$'}value": "#38BDF8", "${'$'}type": "color" },
          "color.accent": { "${'$'}value": "{primitive.sky}", "${'$'}type": "color" }
        }
      }
      """.trimIndent()

    val result = ThemeSchemaValidator.parseAndValidate(json)
    assertTrue(result.isValid)
    val resolved =
      ThemeCascadeResolver.resolve(
        baseDoc = result.document!!,
        parentDoc = BuiltInThemeDocuments.cockpitDark,
        isSystemDark = true,
        appearanceMode = ThemeAppearanceMode.DARK,
        contextual = ContextualEffects(thermalDegradeBlur = true),
        fonts = AutopilotFontSet(),
      )
    assertEquals(Color(0xFF38BDF8), resolved.colors.accent)
    assertEquals(0f, resolved.components.blurRadiusDp.value, 1e-3f)
  }

  @Test
  fun `schema validator rejects invalid hex color token`() {
    val json =
      """
      {
        "id": "broken",
        "name": "Broken",
        "appearance": "light",
        "tokens": {
          "color.accent": { "${'$'}value": "not-a-color", "${'$'}type": "color" }
        }
      }
      """.trimIndent()
    val result = ThemeSchemaValidator.parseAndValidate(json)
    assertFalse(result.isValid)
    assertTrue(result.errors.isNotEmpty())
  }
}

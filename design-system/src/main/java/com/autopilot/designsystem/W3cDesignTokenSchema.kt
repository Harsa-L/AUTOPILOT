package com.autopilot.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * W3C Design Tokens Community Group (DTCG) compatible JSON theme definition.
 */
@Serializable
data class W3cTokenLeaf(
  @SerialName("\$value") val value: String,
  @SerialName("\$type") val type: String = "color",
  @SerialName("\$description") val description: String? = null,
)

@Serializable
data class W3cThemeDocument(
  val schemaVersion: String = "1.0.0",
  val id: String,
  val name: String,
  val appearance: String = "dark",
  val extendsThemeId: String? = null,
  val tokens: Map<String, W3cTokenLeaf> = emptyMap(),
)

data class ThemeValidationResult(
  val isValid: Boolean,
  val errors: List<String>,
  val document: W3cThemeDocument? = null,
)

object ThemeSchemaValidator {
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }

  private val hexRegex = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

  fun parseAndValidate(rawJson: String): ThemeValidationResult {
    val doc =
      runCatching { json.decodeFromString<W3cThemeDocument>(rawJson) }
        .getOrElse { err ->
          return ThemeValidationResult(
            isValid = false,
            errors = listOf("JSON parse error: ${err.message}"),
          )
        }

    val errors = mutableListOf<String>()
    if (doc.id.isBlank()) errors.add("Theme 'id' must not be blank.")
    if (doc.name.isBlank()) errors.add("Theme 'name' must not be blank.")
    if (doc.appearance !in setOf("light", "dark", "system")) {
      errors.add("Theme 'appearance' must be 'light', 'dark', or 'system'.")
    }

    doc.tokens.forEach { (key, leaf) ->
      when (leaf.type) {
        "color" -> {
          val resolved = resolveAlias(leaf.value, doc.tokens)
          if (!hexRegex.matches(resolved)) {
            errors.add("Color token '$key' has invalid hex value '${leaf.value}'.")
          }
        }
        "dimension" -> {
          val num = leaf.value.removeSuffix("dp").toFloatOrNull()
          if (num == null || num < 0f || num > 128f) {
            errors.add("Dimension token '$key' must be 0..128dp (got '${leaf.value}').")
          }
        }
        "number" -> {
          val num = leaf.value.toFloatOrNull()
          if (num == null || num < 0f || num > 1f) {
            errors.add("Number token '$key' must be 0.0..1.0 (got '${leaf.value}').")
          }
        }
      }
    }

    return ThemeValidationResult(
      isValid = errors.isEmpty(),
      errors = errors,
      document = if (errors.isEmpty()) doc else null,
    )
  }

  fun encode(doc: W3cThemeDocument): String = json.encodeToString(W3cThemeDocument.serializer(), doc)

  internal fun resolveAlias(raw: String, table: Map<String, W3cTokenLeaf>, depth: Int = 0): String {
    if (depth > 4) return raw
    if (raw.startsWith("{") && raw.endsWith("}")) {
      val refKey = raw.substring(1, raw.length - 1)
      val target = table[refKey]?.value ?: return raw
      return resolveAlias(target, table, depth + 1)
    }
    return raw
  }

  internal fun parseHexColor(hex: String, fallback: Color): Color {
    val clean = hex.removePrefix("#")
    return runCatching {
        when (clean.length) {
          6 -> Color(clean.toLong(16) or 0xFF000000L)
          8 -> Color(clean.toLong(16))
          else -> fallback
        }
      }
      .getOrDefault(fallback)
  }
}

object BuiltInThemeDocuments {
  val minimalLight =
    W3cThemeDocument(
      id = "minimal_light",
      name = "Minimalist Light Console",
      appearance = "light",
      tokens =
        mapOf(
          "color.canvas" to W3cTokenLeaf("#F4F6F9", "color", "Primary viewport canvas"),
          "color.surface" to W3cTokenLeaf("#FFFFFF", "color", "Elevated panel surface"),
          "color.surfaceElevated" to W3cTokenLeaf("#EDF2F7", "color", "Subtle secondary surface"),
          "color.surfaceGlass" to W3cTokenLeaf("#E0FFFFFF", "color", "HUD translucent overlay"),
          "color.textPrimary" to W3cTokenLeaf("#0F172A", "color"),
          "color.textSecondary" to W3cTokenLeaf("#475569", "color"),
          "color.accent" to W3cTokenLeaf("#0284C7", "color"),
          "color.onAccent" to W3cTokenLeaf("#FFFFFF", "color"),
          "color.borderSubtle" to W3cTokenLeaf("#CBD5E1", "color"),
          "color.statusNominal" to W3cTokenLeaf("#059669", "color"),
          "color.statusWarning" to W3cTokenLeaf("#D97706", "color"),
          "color.statusEmergency" to W3cTokenLeaf("#DC2626", "color"),
          "color.statusInfo" to W3cTokenLeaf("#0284C7", "color"),
          "component.cardRadius" to W3cTokenLeaf("16dp", "dimension"),
          "component.hudAlpha" to W3cTokenLeaf("0.84", "number"),
        ),
    )

  val cockpitDark =
    W3cThemeDocument(
      id = "cockpit_dark",
      name = "Obsidian Cockpit Dark",
      appearance = "dark",
      tokens =
        mapOf(
          "color.canvas" to W3cTokenLeaf("#080C12", "color", "Deep night cockpit canvas"),
          "color.surface" to W3cTokenLeaf("#101722", "color", "Primary telemetry card"),
          "color.surfaceElevated" to W3cTokenLeaf("#172131", "color", "Nested control panel"),
          "color.surfaceGlass" to W3cTokenLeaf("#CC0E1622", "color", "HUD translucent overlay"),
          "color.textPrimary" to W3cTokenLeaf("#F1F5F9", "color"),
          "color.textSecondary" to W3cTokenLeaf("#94A3B8", "color"),
          "color.accent" to W3cTokenLeaf("#38BDF8", "color"),
          "color.onAccent" to W3cTokenLeaf("#06131E", "color"),
          "color.borderSubtle" to W3cTokenLeaf("#243247", "color"),
          "color.statusNominal" to W3cTokenLeaf("#10B981", "color"),
          "color.statusWarning" to W3cTokenLeaf("#F59E0B", "color"),
          "color.statusEmergency" to W3cTokenLeaf("#EF4444", "color"),
          "color.statusInfo" to W3cTokenLeaf("#38BDF8", "color"),
          "component.cardRadius" to W3cTokenLeaf("16dp", "dimension"),
          "component.hudAlpha" to W3cTokenLeaf("0.78", "number"),
        ),
    )

  val daylightHighContrast =
    W3cThemeDocument(
      id = "daylight_contrast",
      name = "Daylight High-Contrast",
      appearance = "light",
      extendsThemeId = "minimal_light",
      tokens =
        mapOf(
          "color.canvas" to W3cTokenLeaf("#FFFFFF", "color"),
          "color.surface" to W3cTokenLeaf("#F8FAFC", "color"),
          "color.textPrimary" to W3cTokenLeaf("#020617", "color"),
          "color.accent" to W3cTokenLeaf("#0369A1", "color"),
          "color.borderSubtle" to W3cTokenLeaf("#64748B", "color"),
          "component.hudAlpha" to W3cTokenLeaf("0.92", "number"),
        ),
    )

  val allPresets: List<W3cThemeDocument> = listOf(minimalLight, cockpitDark, daylightHighContrast)
}

object ThemeCascadeResolver {
  fun resolve(
    baseDoc: W3cThemeDocument,
    parentDoc: W3cThemeDocument?,
    isSystemDark: Boolean,
    appearanceMode: ThemeAppearanceMode,
    contextual: ContextualEffects,
    fonts: AutopilotFontSet,
  ): ResolvedTheme {
    val effectiveDark =
      when (appearanceMode) {
        ThemeAppearanceMode.SYSTEM -> isSystemDark
        ThemeAppearanceMode.DARK -> true
        ThemeAppearanceMode.LIGHT -> false
      }

    val appearanceBase =
      if (effectiveDark) BuiltInThemeDocuments.cockpitDark else BuiltInThemeDocuments.minimalLight

    // 5-layer cascade: Global -> Appearance Base -> Parent Theme -> Selected Theme -> Contextual
    val merged = LinkedHashMap<String, W3cTokenLeaf>()
    merged.putAll(appearanceBase.tokens)
    parentDoc?.tokens?.let { merged.putAll(it) }
    if (baseDoc.id != "system_adaptive") {
      merged.putAll(baseDoc.tokens)
    }

    fun colorOf(key: String, fallback: Color): Color {
      val raw = merged[key]?.value ?: return fallback
      val resolved = ThemeSchemaValidator.resolveAlias(raw, merged)
      return ThemeSchemaValidator.parseHexColor(resolved, fallback)
    }

    fun dpOf(key: String, fallback: Float): androidx.compose.ui.unit.Dp {
      val raw = merged[key]?.value?.removeSuffix("dp")?.toFloatOrNull() ?: fallback
      return raw.dp
    }

    fun floatOf(key: String, fallback: Float): Float =
      merged[key]?.value?.toFloatOrNull() ?: fallback

    val semanticColors =
      SemanticColorTokens(
        canvas = colorOf("color.canvas", if (effectiveDark) Color(0xFF080C12) else Color(0xFFF4F6F9)),
        surface = colorOf("color.surface", if (effectiveDark) Color(0xFF101722) else Color.White),
        surfaceElevated =
          colorOf("color.surfaceElevated", if (effectiveDark) Color(0xFF172131) else Color(0xFFEDF2F7)),
        surfaceGlass =
          colorOf("color.surfaceGlass", if (effectiveDark) Color(0xCC0E1622) else Color(0xE0FFFFFF)),
        textPrimary =
          colorOf("color.textPrimary", if (effectiveDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)),
        textSecondary =
          colorOf("color.textSecondary", if (effectiveDark) Color(0xFF94A3B8) else Color(0xFF475569)),
        accent = colorOf("color.accent", if (effectiveDark) Color(0xFF38BDF8) else Color(0xFF0284C7)),
        onAccent = colorOf("color.onAccent", if (effectiveDark) Color(0xFF06131E) else Color.White),
        borderSubtle =
          colorOf("color.borderSubtle", if (effectiveDark) Color(0xFF243247) else Color(0xFFCBD5E1)),
        statusNominal = colorOf("color.statusNominal", Color(0xFF10B981)),
        statusWarning = colorOf("color.statusWarning", Color(0xFFF59E0B)),
        statusEmergency = colorOf("color.statusEmergency", Color(0xFFEF4444)),
        statusInfo = colorOf("color.statusInfo", Color(0xFF38BDF8)),
        isDark = effectiveDark,
      )

    val baseAlpha = floatOf("component.hudAlpha", 0.80f)
    val components =
      ComponentTokens(
        cardRadius = dpOf("component.cardRadius", 16f),
        hudBackdropAlpha = if (contextual.highContrastHud) 0.94f else baseAlpha,
        blurRadiusDp = if (contextual.thermalDegradeBlur) 0.dp else 12.dp,
      )

    return ResolvedTheme(
      themeId = baseDoc.id,
      themeName = baseDoc.name,
      schemaVersion = baseDoc.schemaVersion,
      primitives = PrimitiveTokens(),
      colors = semanticColors,
      components = components,
      contextual = contextual,
      fonts = fonts,
    )
  }
}

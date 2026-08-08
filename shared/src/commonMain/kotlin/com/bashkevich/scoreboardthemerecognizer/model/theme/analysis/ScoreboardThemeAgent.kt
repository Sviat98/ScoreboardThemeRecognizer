package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/** "The image is not a tennis scoreboard" — surfaced to the user as an error (NOT a fallback). */
class NotAScoreboardException(message: String) : Exception(message)

/**
 * Vision LLM agent that localizes the six scoreboard components (Koog + OpenAI GPT-4o). One
 * structured-output call: the model NEVER names colors, it only returns six normalized (0..1) role
 * boxes. A deterministic OpenCV measurer then reads the exact `#RRGGBB` from the real pixels —
 * so "black instead of navy" cannot happen by construction (port of the proven
 * TennisScoreKeeperBackend `ThemeService` approach, but with coordinate boxes + an OpenCV snap
 * instead of a grid overlay).
 *
 * The OpenAI client is created lazily and reads `OPENAI_API_KEY` on first use, so the absence of a
 * key does not break the rest of the repository (the offline heuristic path still works).
 */
internal class ScoreboardThemeAgent {

    private val executor: PromptExecutor by lazy {
        MultiLLMPromptExecutor(
            OpenAILLMClient(
                readEnvironmentVariable("OPENAI_API_KEY") ?: error("OPENAI_API_KEY is not set")
            )
        )
    }

    suspend fun localize(image: ImageFile): AiComponentLayout {
        val layoutPrompt = prompt("scoreboard_component_layout") {
            system(LOCALIZATION_PROMPT)
            user {
                +"You receive ONE image of a possible tennis scoreboard."
                +"If it is a scoreboard, return exactly six normalized bounding boxes (see the schema)."
                +"Never name or guess colors — a deterministic engine measures them from the pixels afterwards."
                image(
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(image.content),
                        format = imageFormat(image.name),
                        mimeType = imageMime(image.name),
                        fileName = image.name.ifBlank { "scoreboard.png" },
                    )
                )
            }
        }.withUpdatedParams { temperature = 0.0 }

        val result = executor.executeStructured<AiComponentLayout>(
            prompt = layoutPrompt,
            model = OpenAIModels.Chat.GPT4o,
        )
        val structured = result.getOrElse { error ->
            throw IllegalStateException("LLM failed to localize the scoreboard", error)
        }
        return structured.data
    }

    private fun imageFormat(name: String): String =
        when (name.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "jpg", "jpeg" -> "jpeg"
            "png" -> "png"
            else -> "png"
        }

    private fun imageMime(name: String): String = "image/" + imageFormat(name)
}

private val LOCALIZATION_PROMPT = """
    You are an expert at analyzing tennis scoreboards shown in broadcast screenshots. You receive
    ONE image. First decide whether it is a tennis scoreboard.

    CRITICAL RULE: you must NEVER name, estimate or guess any color. A deterministic engine reads
    the exact colors from the real pixels afterwards. Your ONLY job is to locate six UI elements
    and return their positions as bounding boxes.

    Scoreboard anatomy, from RIGHT to LEFT:
      - CURRENT GAME (points) — the rightmost column.
      - CURRENT SET — the column just left of the current game.
      - PREVIOUS (completed) SETS — further left.
      - PLAYER NAMES + SERVE indicator — the leftmost region.
    There are two player rows (one above the other).

    If the image IS a tennis scoreboard, return is_scoreboard=true and "components": exactly SIX
    boxes. Each box must be a TIGHT crop around ONE symbol plus a few pixels of surrounding
    background, using these exact role strings:
      - "main_text"    : one letter from a player's surname + surrounding background. (The engine
        reads BOTH the main background fill AND the main text color from this box.)
      - "serve"        : the serve indicator (small ball/dot/asterisk) next to the name of the ONE
        player who is serving. The other player has no such indicator — pick the side that has it.
      - "prev_set_win" : one digit of the most recent COMPLETED set, belonging to the player who WON
        that set. To decide who won, compare the two players' set digits — the larger number wins.
      - "prev_set_lose": one digit of that same completed set, belonging to the player who LOST it.
      - "current_set"  : one digit of the CURRENT set + its cell background (often a highlighted cell).
      - "current_game" : one digit of the CURRENT game/points + its cell background.

    Each box is { "role", "x", "y", "w", "h" } where (x,y) is the TOP-LEFT corner and (w,h) the size,
    ALL normalized to 0..1 of the image width/height (so they are resolution-independent). Keep each
    box tight around a single symbol but include a little background margin around it.

    If the image is NOT a tennis scoreboard (a person, scenery, another sport, a logo, a screenshot
    of text, etc.), return is_scoreboard=false and a short "reason". Do not return any components.

    Return ONLY the structured result — no explanations, no markdown.
""".trimIndent()

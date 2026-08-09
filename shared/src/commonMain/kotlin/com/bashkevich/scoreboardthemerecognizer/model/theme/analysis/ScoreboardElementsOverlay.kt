package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * Renders the numbered-element overlay (each element's rect + its index) onto the image and returns
 * PNG bytes. This is the image sent to the LLM so it can label each role by element number. OpenCV
 * (`expect`/`actual`).
 */
internal expect fun renderNumberedElementsOverlay(image: ImageFile, elements: ScoreboardElements): ByteArray

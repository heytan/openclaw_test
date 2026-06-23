package com.openclaw.car.agenui

/**
 * Holds the most recently rendered A2UI card JSON (the full createSurface + updateComponents
 * payload). Written by AGenUIFragment.receiveA2UI and BackgroundCardRenderActivity; read by
 * InteractiveCardActivity so the floating bubble can render the same card interactively even
 * when the fragment isn't on screen.
 */
object LatestCardJson {
    @Volatile
    var json: String? = null
    /** The A2UI tab card's rendered width (px), so the bubble card can match it for consistent
     *  background image cropping. */
    @Volatile
    var cardWidth: Int? = null
}

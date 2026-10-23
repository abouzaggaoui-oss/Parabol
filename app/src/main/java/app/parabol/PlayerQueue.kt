package app.parabol

/**
 * Hands the playback queue to PlayerActivity (movies, episodes, catch-up, recordings).
 * For a series this holds the episodes of the selected season. Live TV is played inside
 * the Live TV screen itself and does not use this.
 */
object PlayerQueue {
    data class Item(val url: String, val title: String)

    var items: List<Item> = emptyList()
    var startIndex: Int = 0
}

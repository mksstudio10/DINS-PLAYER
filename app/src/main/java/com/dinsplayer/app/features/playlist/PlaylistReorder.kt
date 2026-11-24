package com.dinsplayer.app.features.playlist

/**
 * Moves the item at [fromIndex] to [toIndex] and returns the updated list.
 * Returns the original list unchanged when indices are equal or out of bounds.
 */
internal fun <T> movePlaylistItem(list: List<T>, fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex) return list
    if (fromIndex !in list.indices || toIndex !in list.indices) return list
    val mutable = list.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}

/**
 * Applies the relative order of [reorderedFilteredIds] back onto [allIds] without disturbing
 * items hidden by an active search filter.
 */
internal fun mergeFilteredOrder(allIds: List<Long>, reorderedFilteredIds: List<Long>): List<Long> {
    val visibleSet = reorderedFilteredIds.toHashSet()
    val result = allIds.toMutableList()
    var nextFilteredIdx = 0
    for (i in result.indices) {
        if (result[i] in visibleSet && nextFilteredIdx < reorderedFilteredIds.size) {
            result[i] = reorderedFilteredIds[nextFilteredIdx++]
        }
    }
    return result
}

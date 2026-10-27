package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionReorderOrdersTest {
    @Test
    fun movedCollectionsKeepTheirSlotsAmongOtherRows() {
        // rows: 0 catalog, 1 A, 2 catalog, 3 B, 4 catalog, 5 C
        val orders = mapOf("catalog_x" to 0, "collection_a" to 1, "catalog_y" to 2, "collection_b" to 3, "catalog_z" to 4, "collection_c" to 5)
        val result = collectionOrdersAfterMove(orders, listOf("collection_c", "collection_a", "collection_b"))
        assertEquals(mapOf("collection_c" to 1, "collection_a" to 3, "collection_b" to 5), result)
    }

    @Test
    fun unchangedOrderYieldsTheSameSlots() {
        val orders = mapOf("collection_a" to 4, "collection_b" to 9)
        assertEquals(orders, collectionOrdersAfterMove(orders, listOf("collection_a", "collection_b")))
    }

    @Test
    fun swappingTwoCollectionsSwapsTheirSlots() {
        val orders = mapOf("collection_a" to 2, "collection_b" to 7)
        assertEquals(mapOf("collection_b" to 2, "collection_a" to 7), collectionOrdersAfterMove(orders, listOf("collection_b", "collection_a")))
    }

    @Test
    fun missingPreferenceMakesTheMoveNotApply() {
        assertNull(collectionOrdersAfterMove(mapOf("collection_a" to 1), listOf("collection_b", "collection_a")))
    }

    @Test
    fun tiedSlotsStayTied() {
        val orders = mapOf("collection_a" to 3, "collection_b" to 3, "collection_c" to 8)
        assertEquals(
            mapOf("collection_c" to 3, "collection_a" to 3, "collection_b" to 8),
            collectionOrdersAfterMove(orders, listOf("collection_c", "collection_a", "collection_b")),
        )
    }
}

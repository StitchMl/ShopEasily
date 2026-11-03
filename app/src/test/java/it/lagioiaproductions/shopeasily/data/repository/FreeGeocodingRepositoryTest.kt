package it.lagioiaproductions.shopeasily.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeGeocodingRepositoryTest {
    @Test
    fun parsesNominatimCoordinates() {
        val point = FreeGeocodingRepository.parse("""[{"lat":"41.9028","lon":"12.4964"}]""")
        assertEquals(41.9028, point?.first ?: 0.0, 0.00001)
        assertEquals(12.4964, point?.second ?: 0.0, 0.00001)
    }

    @Test
    fun rejectsMissingOrInvalidCoordinates() {
        assertNull(FreeGeocodingRepository.parse("[]"))
        assertNull(FreeGeocodingRepository.parse("""[{"lat":"999","lon":"12"}]"""))
    }
}

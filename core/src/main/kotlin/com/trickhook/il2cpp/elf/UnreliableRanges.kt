package com.trickhook.il2cpp.elf

/**
 * A range of file offsets whose bytes are known NOT to be plaintext.
 *
 * This exists because a packed library can hand us a file where part of a section
 * is still ciphertext. Reads from such a range look like perfectly ordinary
 * numbers, so without an explicit record of where they are, the dumper emits
 * confident garbage. Every consumer that reads data through the ELF is expected to
 * ask before trusting what it got.
 */
data class UnreliableRegion(val start: Long, val endExclusive: Long, val reason: String) {
    fun overlaps(offset: Long, length: Long): Boolean =
        length > 0L && offset < endExclusive && offset + length > start
}

class UnreliableRanges(val regions: List<UnreliableRegion>) {

    val isEmpty: Boolean get() = regions.isEmpty()

    fun overlaps(offset: Long, length: Long): Boolean {
        if (regions.isEmpty() || offset < 0L) return false
        for (region in regions) if (region.overlaps(offset, length)) return true
        return false
    }

    fun reasonFor(offset: Long, length: Long): String? {
        if (regions.isEmpty() || offset < 0L) return null
        for (region in regions) if (region.overlaps(offset, length)) return region.reason
        return null
    }

    companion object {
        val NONE = UnreliableRanges(emptyList())

        /**
         * The exact set of bytes the Free Fire style protector rewrites: a window
         * of [windowSize] every [slotStride], phased from the page below the
         * section start, with the final window running to the end of the section.
         * Only these bytes are ciphertext in the packed file; the gaps between the
         * windows are already plaintext.
         */
        fun protectorWindows(
            sectionStart: Long,
            sectionEnd: Long,
            reason: String,
            windowSize: Long = 0x4000L,
            slotStride: Long = 0x10000L,
            firstWindowPhase: Long = 0x2000L
        ): UnreliableRanges {
            if (sectionEnd <= sectionStart) return NONE
            val regions = ArrayList<UnreliableRegion>()
            var at = (sectionStart and 0xFFFL.inv()) + firstWindowPhase
            while (at < sectionEnd) {
                val next = at + slotStride
                // The last window of the section is not clipped to windowSize.
                val end = if (next >= sectionEnd) sectionEnd else minOf(at + windowSize, sectionEnd)
                if (end > at) regions += UnreliableRegion(at, end, reason)
                at = next
            }
            return UnreliableRanges(regions)
        }
    }
}

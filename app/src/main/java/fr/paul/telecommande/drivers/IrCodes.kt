package fr.paul.telecommande.drivers

import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.core.RemoteKey.*

/** Trame infrarouge prête pour ConsumerIrManager.transmit (durées alternées marque/espace en µs). */
class IrFrame(val frequency: Int, val pattern: IntArray)

/** Encodeurs des protocoles IR les plus répandus sur les téléviseurs. */
object IrEncoder {

    private fun lsbBits(value: Int, count: Int) = (0 until count).map { (value shr it) and 1 == 1 }

    /** NEC standard (LG, Toshiba, nombreux modèles asiatiques) – 38 kHz. */
    fun nec(address: Int, command: Int): IrFrame {
        val bytes = if (address > 0xFF) listOf(address and 0xFF, (address shr 8) and 0xFF)
        else listOf(address and 0xFF, address.inv() and 0xFF)
        return pulseDistance(9000, 4500, bytes + listOf(command and 0xFF, command.inv() and 0xFF), 38000)
    }

    /** Samsung32 – 38 kHz, en-tête 4500/4500, adresse répétée. */
    fun samsung(address: Int, command: Int): IrFrame =
        pulseDistance(4500, 4500, listOf(address and 0xFF, address and 0xFF, command and 0xFF, command.inv() and 0xFF), 38000)

    private fun pulseDistance(hMark: Int, hSpace: Int, bytes: List<Int>, freq: Int): IrFrame {
        val p = ArrayList<Int>(4 + bytes.size * 16)
        p += hMark; p += hSpace
        bytes.forEach { b -> lsbBits(b, 8).forEach { bit -> p += 560; p += if (bit) 1690 else 560 } }
        p += 560          // bit d'arrêt
        p += 40000        // silence de fin de trame
        return IrFrame(freq, p.toIntArray())
    }

    /** Sony SIRC 12 bits – 40 kHz, trame de 45 ms répétée 3 fois (exigé par les TV Sony). */
    fun sony12(address: Int, command: Int): IrFrame {
        val one = ArrayList<Int>()
        one += 2400; one += 600
        (lsbBits(command, 7) + lsbBits(address, 5)).forEach { bit -> one += if (bit) 1200 else 600; one += 600 }
        val used = one.sum()
        one[one.size - 1] = one.last() + maxOf(0, 45000 - used)
        val all = ArrayList<Int>()
        repeat(3) { all.addAll(one) }
        return IrFrame(40000, all.toIntArray())
    }

    /** Philips RC5 – 36 kHz, codage Manchester (bit 1 = espace puis marque). */
    fun rc5(address: Int, command: Int, toggle: Boolean): IrFrame {
        val bits = ArrayList<Boolean>()
        bits += true                         // start
        bits += command < 64                 // field (bit 6 inversé)
        bits += toggle
        for (i in 4 downTo 0) bits += (address shr i) and 1 == 1
        for (i in 5 downTo 0) bits += (command shr i) and 1 == 1
        val halves = ArrayList<Boolean>()    // true = marque
        bits.forEach { if (it) { halves += false; halves += true } else { halves += true; halves += false } }
        while (halves.isNotEmpty() && !halves.first()) halves.removeAt(0)
        val p = ArrayList<Int>()
        var cur = halves.first(); var run = 0
        for (h in halves) {
            if (h == cur) run++ else { p += run * 889; cur = h; run = 1 }
        }
        p += run * 889
        if (!cur) p[p.size - 1] = p.last() + 90000 else p += 90000
        return IrFrame(36000, p.toIntArray())
    }
}

/** Codes des marques prises en charge en infrarouge. */
object IrBrands {
    enum class Protocol { NEC, SAMSUNG, SONY12, RC5 }

    class Brand(val id: String, val label: String, val protocol: Protocol, val address: Int, val codes: Map<RemoteKey, Int>)

    private fun digits(vararg c: Int) = RemoteKey.DIGITS.zip(c.toList()).toMap()

    val SAMSUNG = Brand("samsung", "Samsung", Protocol.SAMSUNG, 0x07, mapOf(
        POWER to 0x02, VOL_UP to 0x07, VOL_DOWN to 0x0B, MUTE to 0x0F, CH_UP to 0x12, CH_DOWN to 0x10,
        UP to 0x60, DOWN to 0x61, LEFT to 0x65, RIGHT to 0x62, OK to 0x68, BACK to 0x58,
        HOME to 0x79, MENU to 0x1A, INFO to 0x1F, INPUT to 0x01, EXIT to 0x2D,
        PLAY_PAUSE to 0x47, STOP to 0x46, REWIND to 0x45, FAST_FORWARD to 0x48,
        RED to 0x6C, GREEN to 0x14, YELLOW to 0x15, BLUE to 0x16
    ) + digits(0x11, 0x04, 0x05, 0x06, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E))

    val LG = Brand("lg", "LG", Protocol.NEC, 0x04, mapOf(
        POWER to 0x08, VOL_UP to 0x02, VOL_DOWN to 0x03, MUTE to 0x09, CH_UP to 0x00, CH_DOWN to 0x01,
        UP to 0x40, DOWN to 0x41, LEFT to 0x07, RIGHT to 0x06, OK to 0x44, BACK to 0x28,
        HOME to 0x7C, MENU to 0x43, INFO to 0xAA, INPUT to 0x0B, EXIT to 0x5B,
        PLAY_PAUSE to 0xB0, STOP to 0xB1, REWIND to 0x8F, FAST_FORWARD to 0x8E,
        RED to 0x72, GREEN to 0x71, YELLOW to 0x63, BLUE to 0x61
    ) + digits(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19))

    val SONY = Brand("sony", "Sony", Protocol.SONY12, 0x01, mapOf(
        POWER to 21, VOL_UP to 18, VOL_DOWN to 19, MUTE to 20, CH_UP to 16, CH_DOWN to 17,
        UP to 116, DOWN to 117, LEFT to 52, RIGHT to 51, OK to 101, HOME to 96, INPUT to 37, INFO to 58,
        BACK to 99, EXIT to 99
    ) + digits(9, 0, 1, 2, 3, 4, 5, 6, 7, 8))

    val PHILIPS = Brand("philips", "Philips (RC5)", Protocol.RC5, 0x00, mapOf(
        POWER to 12, VOL_UP to 16, VOL_DOWN to 17, MUTE to 13, CH_UP to 32, CH_DOWN to 33,
        UP to 80, DOWN to 81, LEFT to 85, RIGHT to 86, OK to 87, MENU to 82, BACK to 10, INFO to 15,
        INPUT to 56, PLAY_PAUSE to 53, STOP to 54, REWIND to 50, FAST_FORWARD to 52
    ) + digits(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))

    val TOSHIBA = Brand("toshiba", "Toshiba", Protocol.NEC, 0x40, mapOf(
        POWER to 0x12, VOL_UP to 0x1A, VOL_DOWN to 0x1E, MUTE to 0x10, CH_UP to 0x1B, CH_DOWN to 0x1F,
        INPUT to 0x0F, MENU to 0x80, UP to 0x19, DOWN to 0x1D, LEFT to 0x42, RIGHT to 0x40, OK to 0x21,
        BACK to 0x3B, EXIT to 0x1C
    ) + digits(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09))

    val ALL = listOf(SAMSUNG, LG, SONY, PHILIPS, TOSHIBA)
    const val AUTO = "auto"

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    fun frame(brand: Brand, key: RemoteKey, toggle: Boolean): IrFrame? {
        val code = brand.codes[key] ?: return null
        return when (brand.protocol) {
            Protocol.NEC -> IrEncoder.nec(brand.address, code)
            Protocol.SAMSUNG -> IrEncoder.samsung(brand.address, code)
            Protocol.SONY12 -> IrEncoder.sony12(brand.address, code)
            Protocol.RC5 -> IrEncoder.rc5(brand.address, code, toggle)
        }
    }
}

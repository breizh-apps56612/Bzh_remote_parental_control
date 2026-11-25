package fr.paul.telecommande.drivers

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Encodeur protobuf minimal (varint, chaînes, octets, sous-messages) – sans dépendance. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) = apply { tag(field, 0); Proto.writeVarint(out, value) }
    fun varint(field: Int, value: Int) = varint(field, value.toLong())
    fun bytes(field: Int, value: ByteArray) = apply { tag(field, 2); Proto.writeVarint(out, value.size.toLong()); out.write(value) }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, block: ProtoWriter.() -> Unit) = bytes(field, ProtoWriter().apply(block).toByteArray())

    private fun tag(field: Int, wire: Int) = Proto.writeVarint(out, ((field shl 3) or wire).toLong())
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Message protobuf décodé : numéro de champ -> valeurs (Long pour varint, ByteArray pour longueur). */
class ProtoMessage(val fields: Map<Int, List<Any>>) {
    fun has(field: Int) = fields.containsKey(field)
    fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { Proto.parse(it) }
}

object Proto {
    fun writeVarint(out: OutputStream, v: Long) {
        var value = v
        while (true) {
            if (value and 0x7FL.inv() == 0L) { out.write(value.toInt()); return }
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
    }

    fun readVarint(input: InputStream): Long {
        var shift = 0; var result = 0L
        while (shift < 64) {
            val b = input.read()
            if (b < 0) throw EOFException("Flux fermé")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalStateException("Varint invalide")
    }

    fun parse(data: ByteArray): ProtoMessage {
        val input = data.inputStream()
        val map = LinkedHashMap<Int, MutableList<Any>>()
        while (input.available() > 0) {
            val tag = readVarint(input)
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> map.getOrPut(field) { mutableListOf() } += readVarint(input)
                1 -> input.skip(8)
                2 -> {
                    val len = readVarint(input).toInt()
                    val buf = ByteArray(len)
                    var off = 0
                    while (off < len) { val r = input.read(buf, off, len - off); if (r < 0) break; off += r }
                    map.getOrPut(field) { mutableListOf() } += buf
                }
                5 -> input.skip(4)
                else -> throw IllegalStateException("Type de champ inconnu")
            }
        }
        return ProtoMessage(map)
    }

    /** Écrit un message précédé de sa longueur (varint). */
    fun writeDelimited(out: OutputStream, msg: ByteArray) {
        val buf = ByteArrayOutputStream()
        writeVarint(buf, msg.size.toLong()); buf.write(msg)
        out.write(buf.toByteArray()); out.flush()
    }

    fun readDelimited(input: InputStream): ByteArray {
        val len = readVarint(input).toInt()
        if (len < 0 || len > 1_000_000) throw IllegalStateException("Taille de message invalide")
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r < 0) throw EOFException("Flux fermé")
            off += r
        }
        return buf
    }
}

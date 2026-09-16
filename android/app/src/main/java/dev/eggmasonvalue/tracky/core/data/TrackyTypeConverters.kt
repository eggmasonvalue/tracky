package dev.eggmasonvalue.tracky.core.data

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class TrackyTypeConverters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun sessionStatusToStorage(value: SessionStatus?): String? = value?.name

    @TypeConverter
    fun storageToSessionStatus(value: String?): SessionStatus? =
        value?.let(SessionStatus::valueOf)

    @TypeConverter
    fun displayUnitToStorage(value: DisplayUnit?): String? = value?.name

    @TypeConverter
    fun storageToDisplayUnit(value: String?): DisplayUnit? = value?.let(DisplayUnit::valueOf)

    @TypeConverter
    fun directionToStorage(value: FrameDirection?): String? = value?.name

    @TypeConverter
    fun storageToDirection(value: String?): FrameDirection? = value?.let(FrameDirection::valueOf)

    @TypeConverter
    fun resistanceValuesToBlob(values: List<Int>?): ByteArray? =
        values?.let(ResistanceValuesCodec::encode)

    @TypeConverter
    fun blobToResistanceValues(blob: ByteArray?): List<Int>? =
        blob?.let(ResistanceValuesCodec::decode)
}

/** Fixed-width, count-prefixed encoding: order and every unsigned-16 value round-trip exactly. */
object ResistanceValuesCodec {
    fun encode(values: List<Int>): ByteArray {
        require(values.all { it in 0..0xffff }) {
            "resistance values must be unsigned 16-bit values"
        }
        require(values.size <= (Int.MAX_VALUE - Integer.BYTES) / Short.SIZE_BYTES) {
            "too many resistance values"
        }

        return ByteBuffer.allocate(Integer.BYTES + values.size * Short.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(values.size)
            .also { buffer -> values.forEach { buffer.putShort(it.toShort()) } }
            .array()
    }

    fun decode(blob: ByteArray): List<Int> {
        require(blob.size >= Integer.BYTES) { "invalid resistance encoding" }
        val buffer = ByteBuffer.wrap(blob.copyOf()).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.int
        require(count >= 0 && count <= (blob.size - Integer.BYTES) / Short.SIZE_BYTES) {
            "invalid resistance count"
        }
        require(blob.size == Integer.BYTES + count * Short.SIZE_BYTES) {
            "invalid resistance encoding length"
        }

        return immutableListCopy(List(count) { buffer.short.toInt() and 0xffff })
    }
}

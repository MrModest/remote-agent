package dev.remoteagent.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Mirrors apple/RAKit/Sources/RAKit/Protocol/Coding.swift. */
object WireCoding {
    val json = Json {
        // Clients ignore fields they don't know (protocol/PROTOCOL.md).
        ignoreUnknownKeys = true
        // Optionals that are nil are left out, as Codable does.
        explicitNulls = false
        // Defaults such as a new commandId are sent.
        encodeDefaults = true
    }

    /** Go marshals times as RFC 3339 with up to nanosecond precision. */
    fun parseDate(s: String): Instant? = try {
        OffsetDateTime.parse(s).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        val s = decoder.decodeString()
        return WireCoding.parseDate(s) ?: throw SerializationException("bad date $s")
    }
}

/** An enum as the strings on the wire. */
interface WireEnum {
    val wire: String
}

/** Decodes values it doesn't know as `fallback`, so newer servers don't break older apps. */
open class LenientEnumSerializer<E>(name: String, private val values: Array<E>, private val fallback: E) : KSerializer<E>
        where E : Enum<E>, E : WireEnum {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): E {
        val s = decoder.decodeString()
        return values.firstOrNull { it.wire == s } ?: fallback
    }
}

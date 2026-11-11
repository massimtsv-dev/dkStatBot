package com.statbot.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Экранирование для parse_mode=HTML. */
fun String.esc(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

fun LocalDate.weekMonday(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun LocalDate.firstMondayOfMonth(): LocalDate = withDayOfMonth(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

fun numbered(items: List<String>): String = items.mapIndexed { i, t -> "${i + 1}. ${t.esc()}" }.joinToString("\n")

private class LocalDateAdapter : TypeAdapter<LocalDate>() {
    override fun write(out: JsonWriter, value: LocalDate?) {
        if (value == null) out.nullValue() else out.value(value.toString())
    }

    override fun read(reader: JsonReader): LocalDate? {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null }
        return LocalDate.parse(reader.nextString())
    }
}

val gson: Gson = GsonBuilder().registerTypeAdapter(LocalDate::class.java, LocalDateAdapter()).create()

package com.example.dev

import android.util.Log
import com.example.BuildConfig
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Short, readable events about what the app decided (model switched, re-index offered, ...), shown in the
 * dev build's Developer console next to the raw system log. Always forwarded to logcat; kept in memory only
 * in the dev build, so the prod build stores nothing.
 */
object DevLog {

    class Line(val time: Long, val level: Char, val tag: String, val message: String) {
        override fun toString(): String =
            "${FORMAT.get()!!.format(Date(time))} $level/$tag: $message"
    }

    private const val MAX_LINES = 500
    private val lines = ArrayDeque<Line>()
    private val FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun i(tag: String, message: String) = add('I', tag, message)
    fun w(tag: String, message: String) = add('W', tag, message)
    fun e(tag: String, message: String) = add('E', tag, message)

    private fun add(level: Char, tag: String, message: String) {
        when (level) {
            'W' -> Log.w(tag, message)
            'E' -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
        if (!BuildConfig.DEV_MODE) return
        synchronized(lines) {
            lines.addFirst(Line(System.currentTimeMillis(), level, tag, message))
            while (lines.size > MAX_LINES) lines.removeLast()
        }
    }

    /** Newest first. */
    fun snapshot(): List<Line> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}

package com.lumora.scraper.utils

import android.util.Log
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * The subset of upstream's `Extensions.kt` the ported scrapers actually call. The rest of that
 * file was Fragment/ViewModel/navigation glue for an app structure Lumora does not share.
 */

/**
 * Parses whatever shape a scraped release date arrived in. Every site writes it differently and
 * none of them say which - a bare year, a localised long form, an ISO timestamp - so this tries
 * the known patterns in turn and gives up rather than throwing, because a title with an
 * unparseable date should still list.
 */
fun String.toCalendar(): Calendar? {
    val patterns = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
        SimpleDateFormat("d MMMM yyyy ('USA')", Locale.ENGLISH),
        SimpleDateFormat("d MMMM yyyy", Locale.FRENCH),
        SimpleDateFormat("yyyy", Locale.ENGLISH),
        SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH),
        SimpleDateFormat("MMMM d, yyyy ('United' 'States')", Locale.ENGLISH),
        SimpleDateFormat("MMM. d, yyyy", Locale.ENGLISH),
    )
    patterns.forEach { sdf ->
        try {
            return Calendar.getInstance().also { it.time = sdf.parse(this)!! }
        } catch (_: Exception) {
        }
    }
    return null
}

fun Calendar.format(pattern: String): String? = try {
    SimpleDateFormat(pattern, Locale.getDefault()).format(this.time)
} catch (_: Exception) {
    null
}

/** Runs [predicate] up to [retries] times, rethrowing the last failure if none succeed. */
suspend fun <T> retry(retries: Int, predicate: suspend (attempt: Int) -> T): T {
    require(retries > 0) { "Expected positive amount of retries, but had $retries" }
    var throwable: Throwable? = null
    (1..retries).forEach { attempt ->
        try {
            return predicate(attempt)
        } catch (e: Throwable) {
            throwable = e
        }
    }
    throw throwable!!
}

/** [subList] that clamps instead of throwing - scraped page sizes are not what they claim. */
fun <T> List<T>.safeSubList(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex > toIndex) return emptyList()
    return subList(
        max(min(fromIndex.coerceAtLeast(0), size), 0),
        max(min(toIndex.coerceAtMost(size), size), 0)
    )
}

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, V?>.filterNotNullValues() = filterValues { it != null } as Map<K, V>

fun String.toSubtitleMimeType(): String = when {
    endsWith("vtt", true) -> MimeTypes.TEXT_VTT
    endsWith("srt", true) -> MimeTypes.APPLICATION_SUBRIP
    endsWith("xml", true) || endsWith("ttml", true) -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.APPLICATION_SUBRIP
}

/**
 * [async] that yields null instead of cancelling its parent scope on failure. Scraper fan-out
 * routinely fires a dozen host lookups at once expecting several to be dead.
 */
fun <T> CoroutineScope.asyncOrNull(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): Deferred<T?> = async(context, start) {
    try {
        block()
    } catch (e: Exception) {
        Log.e("ScraperExtensions", "asyncOrNull: ", e)
        null
    }
}

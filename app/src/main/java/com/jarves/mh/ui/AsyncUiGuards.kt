package com.jarves.mh.ui

import java.util.concurrent.atomic.AtomicLong

/** Keeps only the newest output, bounding the backing buffer as well as the UI text. */
internal class BoundedTerminalOutput(private val maxChars: Int) {
    private val buffer = StringBuilder()

    init {
        require(maxChars > 0)
    }

    val length: Int get() = buffer.length

    fun append(text: String) {
        if (text.length >= maxChars) {
            buffer.setLength(0)
            buffer.append(text, text.length - maxChars, text.length)
        } else {
            val overflow = buffer.length + text.length - maxChars
            if (overflow > 0) buffer.delete(0, overflow)
            buffer.append(text)
        }
    }

    override fun toString(): String = buffer.toString()
}

/** A request generation also distinguishes reopening the same path after closing it. */
internal class LatestFileRead {
    data class Request(val generation: Long, val projectId: String, val path: String)

    private val generation = AtomicLong()

    fun begin(projectId: String, path: String): Request =
        Request(generation.incrementAndGet(), projectId, path)

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(request: Request, projectId: String?, path: String?): Boolean =
        request.generation == generation.get() && request.projectId == projectId && request.path == path
}

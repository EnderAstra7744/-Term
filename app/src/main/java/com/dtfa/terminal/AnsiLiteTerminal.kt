package com.dtfa.terminal

import java.io.ByteArrayOutputStream

/**
 * A deliberately simple terminal buffer.
 *
 * It is NOT a full VT100/xterm emulator: it does not do on-screen cursor
 * positioning, colors, or full-screen redraws. What it DOES do is turn the
 * raw byte stream from bash/apt/etc. into readable scrollback text, which
 * covers the vast majority of everyday shell usage (ls, cd, apt, pip, gcc,
 * python, git, ...). Full-screen apps like vim/htop/nano will show their
 * raw content but won't repaint correctly in place.
 *
 * If you later want a real terminal (colors, cursor movement, vim/htop
 * support), swap this out for com.termux's terminal-view/terminal-emulator
 * libraries — see README.md for pointers.
 */
class AnsiLiteTerminal(private val maxLines: Int = 2000) {

    private val lines = ArrayDeque<StringBuilder>().apply { add(StringBuilder()) }
    private val pending = ByteArrayOutputStream()

    // Escape-sequence parsing state.
    private var inEscape = false
    private var inCsiOrOsc = false
    private var oscMode = false

    @Synchronized
    fun feed(bytes: ByteArray, len: Int): String {
        pending.write(bytes, 0, len)
        val all = pending.toByteArray()

        var consumedUpTo = 0
        var j = 0
        while (j < all.size) {
            val b = all[j].toInt() and 0xFF
            if (inEscape) {
                j = consumeEscape(all, j)
                continue
            }
            when (b) {
                0x1B -> { inEscape = true; j++ }
                '\n'.code -> { newline(); j++ }
                '\r'.code -> { currentLine().setLength(0); j++ }
                0x08 -> { // backspace
                    val cur = currentLine()
                    if (cur.isNotEmpty()) cur.setLength(cur.length - 1)
                    j++
                }
                0x07 -> j++ // BEL, ignore
                else -> {
                    // Decode one UTF-8 codepoint starting here.
                    val charLen = utf8Len(b)
                    if (j + charLen > all.size) break // wait for more bytes
                    val chunk = String(all, j, charLen, Charsets.UTF_8)
                    currentLine().append(chunk)
                    j += charLen
                }
            }
            consumedUpTo = j
        }

        // Keep unconsumed tail (partial escape / partial UTF-8) for next feed.
        val remainder = all.copyOfRange(consumedUpTo, all.size)
        pending.reset()
        pending.write(remainder)

        while (lines.size > maxLines) lines.removeFirst()
        return render()
    }

    private fun utf8Len(firstByte: Int): Int = when {
        firstByte and 0x80 == 0x00 -> 1
        firstByte and 0xE0 == 0xC0 -> 2
        firstByte and 0xF0 == 0xE0 -> 3
        firstByte and 0xF8 == 0xF0 -> 4
        else -> 1
    }

    private fun currentLine(): StringBuilder = lines.last()

    private fun newline() {
        lines.addLast(StringBuilder())
    }

    /** Consumes one escape sequence starting at `esc[i]=='\x1B'`-following byte; returns new index. */
    private fun consumeEscape(all: ByteArray, startIdx: Int): Int {
        var i = startIdx
        val b0 = all.getOrNull(i)?.toInt()?.and(0xFF) ?: run { return i }
        when (b0.toChar()) {
            '[' -> { // CSI: ESC [ params letter
                i++
                while (i < all.size) {
                    val c = all[i].toInt() and 0xFF
                    i++
                    if (c in 0x40..0x7E) break // final byte
                }
                inEscape = false
                return i
            }
            ']' -> { // OSC: ESC ] ... BEL or ESC \
                i++
                while (i < all.size) {
                    val c = all[i].toInt() and 0xFF
                    if (c == 0x07) { i++; break }
                    if (c == 0x1B && (all.getOrNull(i + 1)?.toInt()?.and(0xFF)) == '\\'.code) { i += 2; break }
                    i++
                }
                inEscape = false
                return i
            }
            else -> {
                // Single-character escape (e.g. ESC M, ESC 7, ESC =). Consume one byte.
                i++
                inEscape = false
                return i
            }
        }
    }

    @Synchronized
    fun render(): String = lines.joinToString("\n") { it.toString() }

    @Synchronized
    fun clear() {
        lines.clear()
        lines.add(StringBuilder())
    }
}

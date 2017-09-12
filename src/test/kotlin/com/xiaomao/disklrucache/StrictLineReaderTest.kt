package com.xiaomao.disklrucache

import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class StrictLineReaderTest {
    @Test
    fun lineReaderConsistencyWithReadAsciiLine() {
        try {
            // Testing with LineReader buffer capacity 32 to check some corner cases.
            val lineReader = StrictLineReader(createTestInputStream(), 32, Charsets.US_ASCII)
            val refStream = createTestInputStream()
            while (true) {
                try {
                    val refLine = readAsciiLine(refStream)
                    try {
                        val line = lineReader.readLine()
                        if (refLine != line) {
                            Assert.fail("line (\"$line\") differs from expected (\"$refLine\").")
                        }
                    } catch (eof: EOFException) {
                        Assert.fail("line reader threw EOFException too early.")
                    }

                } catch (refEof: EOFException) {
                    try {
                        lineReader.readLine()
                        Assert.fail("line reader didn't throw the expected EOFException.")
                    } catch (expected: EOFException) {
                        break
                    }

                }

            }
            refStream.close()
            lineReader.close()
        } catch (ioe: IOException) {
            Assert.fail("Unexpected IOException " + ioe.toString())
        }

    }

    /* XXX From libcore.io.Streams */
    @Throws(IOException::class)
    private fun readAsciiLine(ins: InputStream): String {

        val result = StringBuilder(80)
        while (true) {
            val c = ins.read()
            if (c == -1) {
                throw EOFException()
            } else if (c == '\n'.toInt()) {
                break
            }

            result.append(c.toChar())
        }
        val length = result.length
        if (length > 0 && result[length - 1] == '\r') {
            result.setLength(length - 1)
        }
        return result.toString()
    }

    private fun createTestInputStream(): InputStream {
        return ByteArrayInputStream((""
                // Each source lines below should represent 32 bytes, until the next comment.
                + "12 byte line\n18 byte line......\n"
                + "pad\nline spanning two 32-byte bu"
                + "ffers\npad......................\n"
                + "pad\nline spanning three 32-byte "
                + "buffers and ending with LF at th"
                + "e end of a 32 byte buffer......\n"
                + "pad\nLine ending with CRLF split"
                + " at the end of a 32-byte buffer\r"
                + "\npad...........................\n"
                // End of 32-byte lines.
                + "line ending with CRLF\r\n"
                + "this is a long line with embedded CR \r ending with CRLF and having more than "
                + "32 characters\r\n"
                + "unterminated line - should be dropped").toByteArray())
    }
}
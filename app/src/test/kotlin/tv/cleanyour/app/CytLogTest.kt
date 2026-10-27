package tv.cleanyour.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class CytLogTest {
    @Test fun oversizedLogLinesDoNotExceedTheFileLimit() = withLogDir { dir ->
        CytLog.d("x?".repeat(550_000))

        val logFile = File(dir, "session-0.log")
        assertTrue(logFile.length() <= MAX_LOG_FILE_BYTES, "oversized line wrote ${logFile.length()} bytes")
        assertTrue(CytLog.snapshot().endsWith("…[truncated]\n"), "truncation marker is missing")
        assertTrue(CytLog.snapshot().length <= MAX_LOG_LINE_CHARS + 1)
    }

    @Test fun fileRotatesBeforeNextLineWouldExceedTheLimit() = withLogDir { dir ->
        File(dir, "session-0.log").writeText("x".repeat((MAX_LOG_FILE_BYTES - 500).toInt()))

        CytLog.d("y?".repeat(500))

        val current = File(dir, "session-0.log")
        val previous = File(dir, "session-1.log")
        assertTrue(current.length() <= MAX_LOG_FILE_BYTES, "current log grew to ${current.length()} bytes")
        assertTrue(previous.exists(), "full current log should have been rotated")
        assertTrue(previous.length() <= MAX_LOG_FILE_BYTES)
    }

    private fun withLogDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("mi5g-cytlog-test").toFile()
        CytLog.init(dir)
        CytLog.clear()
        try {
            block(dir)
        } finally {
            CytLog.clear()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val MAX_LOG_FILE_BYTES = 1_000_000L
        const val MAX_LOG_LINE_CHARS = 2_000
    }
}

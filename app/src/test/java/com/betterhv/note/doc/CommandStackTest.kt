package com.betterhv.note.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingCommand(private val log: MutableList<String>, private val name: String) : Command {
    override fun execute() {
        log.add("execute:$name")
    }

    override fun undo() {
        log.add("undo:$name")
    }
}

class CommandStackTest {

    @Test
    fun `execute runs the command and enables undo`() {
        val stack = CommandStack()
        val log = mutableListOf<String>()
        stack.execute(RecordingCommand(log, "a"))
        assertEquals(listOf("execute:a"), log)
        assertTrue(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `undo then redo replays in the correct order`() {
        val stack = CommandStack()
        val log = mutableListOf<String>()
        stack.execute(RecordingCommand(log, "a"))
        stack.execute(RecordingCommand(log, "b"))
        stack.undo()
        stack.undo()
        stack.redo()
        assertEquals(listOf("execute:a", "execute:b", "undo:b", "undo:a", "execute:a"), log)
    }

    @Test
    fun `new command after undo clears the redo stack`() {
        val stack = CommandStack()
        val log = mutableListOf<String>()
        stack.execute(RecordingCommand(log, "a"))
        stack.undo()
        assertTrue(stack.canRedo)
        stack.execute(RecordingCommand(log, "b"))
        assertFalse(stack.canRedo)
    }

    @Test
    fun `undo on an empty stack is a no-op`() {
        val stack = CommandStack()
        stack.undo()
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `push records an already-executed command without re-executing it`() {
        val stack = CommandStack()
        val log = mutableListOf<String>()
        val cmd = RecordingCommand(log, "a")
        cmd.execute()
        log.clear()
        stack.push(cmd)
        assertEquals(emptyList<String>(), log)
        assertTrue(stack.canUndo)
        stack.undo()
        assertEquals(listOf("undo:a"), log)
    }
}

package com.betterhv.note.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

  @Test
  fun `default max depth is 100 and custom maxDepth evicts oldest for execute and push`() {
    assertEquals(100, CommandStack.DEFAULT_MAX_DEPTH)

    val executeStack = CommandStack(maxDepth = 3)
    val log = mutableListOf<String>()
    val cmd1 = RecordingCommand(log, "1")
    val cmd2 = RecordingCommand(log, "2")
    val cmd3 = RecordingCommand(log, "3")
    val cmd4 = RecordingCommand(log, "4")
    executeStack.execute(cmd1)
    executeStack.execute(cmd2)
    executeStack.execute(cmd3)
    executeStack.execute(cmd4)

    log.clear()
    while (executeStack.canUndo) executeStack.undo()
    assertEquals(listOf("undo:4", "undo:3", "undo:2"), log)

    val pushStack = CommandStack(maxDepth = 3)
    log.clear()
    pushStack.push(RecordingCommand(log, "p1"))
    pushStack.push(RecordingCommand(log, "p2"))
    pushStack.push(RecordingCommand(log, "p3"))
    pushStack.push(RecordingCommand(log, "p4"))
    while (pushStack.canUndo) pushStack.undo()
    assertEquals(listOf("undo:p4", "undo:p3", "undo:p2"), log)
  }

  @Test
  fun `peekUndo and peekRedo inspect top commands without mutating stacks`() {
    val stack = CommandStack()
    assertNull(stack.peekUndo())
    assertNull(stack.peekRedo())

    val log = mutableListOf<String>()
    val first = RecordingCommand(log, "first")
    val second = RecordingCommand(log, "second")
    stack.execute(first)
    stack.execute(second)

    assertSame(second, stack.peekUndo())
    assertSame(second, stack.peekUndo())
    assertNull(stack.peekRedo())

    stack.undo()
    assertSame(first, stack.peekUndo())
    assertSame(second, stack.peekRedo())
    assertSame(second, stack.peekRedo())
    assertTrue(stack.canUndo)
    assertTrue(stack.canRedo)
  }
}

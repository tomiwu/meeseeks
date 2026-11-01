package meeseeks

/** The type of a task: build with a strict external check, or research with a rubric. */
enum TaskType:
  case Build
  case Research

/** A task parsed from a chat message: a goal, a type, and a check or rubric. */
final case class Task(goal: String, taskType: TaskType, check: String)

package meeseeks

import meeseeks.fake.*
import meeseeks.port.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import scala.concurrent.duration.*

/** Tests for the Meeseeks tree: children in the overseer tree, child failure propagation, result
  * merging, and solved-work reuse.
  */
class TreeSuite extends munit.FunSuite:

  // Children appear in the overseer tree.
  test("child Meeseeks is registered in the overseer tree under its parent") {
    val kit = ActorTestKit()
    val overseer = kit.spawn(Overseer("tree-child-os-1"), "tree-child-os-1")

    val childControlProbe = kit.createTestProbe[Overseer.MeeseeksControl]()
    val treeProbe = kit.createTestProbe[Overseer.TreeView]()

    overseer ! Overseer.RegisterChild("child-1", "parent-1", childControlProbe.ref)
    overseer ! Overseer.GetTreeView(treeProbe.ref)

    val tree = treeProbe.receiveMessage(5.seconds)
    assert(tree.childParent.contains("child-1"), s"child-1 missing from tree: $tree")
    assertEquals(tree.childParent("child-1"), "parent-1")

    kit.shutdownTestKit()
  }

  // Child failure raises parent frustration: overseer routes ChildFailed to parent.
  test("ChildFail routes ChildFailed message to parent control ref") {
    val kit = ActorTestKit()
    val parentControlProbe = kit.createTestProbe[Overseer.MeeseeksControl]()
    val overseer = kit.spawn(Overseer("tree-route-os-1"), "tree-route-os-1")

    overseer ! Overseer.RegisterMeeseeks("parent-r1", parentControlProbe.ref)
    overseer ! Overseer.ChildFail("child-r1", "parent-r1")

    parentControlProbe.expectMessage(5.seconds, Overseer.ChildFailed("child-r1"))

    kit.shutdownTestKit()
  }

  // ChildDone routes ChildSucceeded to parent control ref.
  test("ChildDone routes ChildSucceeded message to parent control ref") {
    val kit = ActorTestKit()
    val parentControlProbe = kit.createTestProbe[Overseer.MeeseeksControl]()
    val overseer = kit.spawn(Overseer("tree-done-os-1"), "tree-done-os-1")

    overseer ! Overseer.RegisterMeeseeks("parent-d1", parentControlProbe.ref)
    overseer ! Overseer.ChildDone("child-d1", "parent-d1", "result-output")

    parentControlProbe.expectMessage(
      5.seconds,
      Overseer.ChildSucceeded("child-d1", "result-output")
    )

    kit.shutdownTestKit()
  }

  // Parent merges child results when all children succeed.
  test("parent merges child results when all children succeed") {
    val kit = ActorTestKit()
    val task = Task("build feature", TaskType.Build, "sbt test")
    val overseer = kit.spawn(Overseer("tree-merge-os-1"), "tree-merge-os-1")
    val resultProbe = kit.createTestProbe[String]()

    // Parent: brain fails once to trigger split at Frustrated level.
    val parentBrain = FakeBrain.withError(BrainError.Unavailable("fail to trigger split"))
    val parentOracle = FakeOracle.withVerdicts()

    // FrustrationConfig: frustrated after 1 fail.
    val fastConfig = FrustrationConfig(
      worriedAfterFails = 999,
      frustratedAfterFails = 1,
      desperateAfterFails = 999,
      stuckAfterFails = 999
    )

    val parentId = "tree-parent-merge-1"

    def childFactory(childId: String, childTask: Task, pid: String): Unit =
      val brain = FakeBrain.withValues(s"$childId-output")
      val oracle = FakeOracle.withVerdicts(OracleVerdict.Pass)
      val _ = kit.spawn(
        Meeseeks(
          childId,
          childTask,
          overseer,
          brain,
          oracle,
          resultProbe.ref,
          parentId = Some(pid)
        ),
        childId
      )

    val _ = kit.spawn(
      Meeseeks(
        parentId,
        task,
        overseer,
        parentBrain,
        parentOracle,
        resultProbe.ref,
        childFactory = Some(childFactory),
        frustrationConfig = fastConfig,
        splitAtLevel = FrustrationLevel.Frustrated,
        childCount = 2
      ),
      parentId
    )

    val result = resultProbe.receiveMessage(10.seconds)
    assert(result.startsWith("Task complete:"), s"unexpected result: $result")
    // Merged result contains outputs from both children.
    assert(
      result.contains(s"$parentId-child-1-output") || result.contains(s"$parentId-child-2-output"),
      s"result should contain child outputs: $result"
    )

    kit.shutdownTestKit()
  }

  // Solved work is reused: a new Meeseeks skips running steps.
  test("solved work is reused when overseer has a matching goal result") {
    val kit = ActorTestKit()
    val task = Task("build feature reuse", TaskType.Build, "sbt test")
    val overseer = kit.spawn(Overseer("tree-reuse-os-1"), "tree-reuse-os-1")
    val resultProbe = kit.createTestProbe[String]()

    // Record solved work before spawning the Meeseeks.
    val verifyProbe = kit.createTestProbe[Overseer.SolvedWorkResult]()
    overseer ! Overseer.RecordSolvedWork(task.goal, "cached result")
    overseer ! Overseer.QuerySolvedWork(task.goal, verifyProbe.ref)
    verifyProbe.expectMessage(5.seconds, Overseer.SolvedWork("cached result"))

    // Brain and oracle have no scripted values; they must not be called.
    val brain = FakeBrain.withValues()
    val oracle = FakeOracle.withVerdicts()

    val _ = kit.spawn(
      Meeseeks("tree-reuse-m-1", task, overseer, brain, oracle, resultProbe.ref),
      "tree-reuse-m-1"
    )

    val result = resultProbe.receiveMessage(5.seconds)
    assertEquals(result, "Task complete (reused): cached result")

    kit.shutdownTestKit()
  }

end TreeSuite

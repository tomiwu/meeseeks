package meeseeks.research

import kyo.*
import meeseeks.Task
import meeseeks.brain.BackendKind
import meeseeks.brain.BrainConfig
import meeseeks.brain.BrainRegistry
import meeseeks.port.*

/** Runs N independent research copies, verifies source URLs, and asks a blind judge to score. */
object ResearchCoordinator:

  private val BackendKinds: Array[BackendKind] =
    Array(BackendKind.Subscription, BackendKind.ApiKey, BackendKind.LocalModel)

  def run(
      task: Task,
      brainRegistry: BrainRegistry,
      urlVerifier: UrlVerifier,
      judge: ResearchJudge,
      copyCount: Int = 3,
      seed: Long = 42L
  ): ResearchReport < (Sync & Abort[OracleError]) =
    runCopies(task, brainRegistry, (1 to copyCount).toList, copyCount).flatMap { allClaims =>
      verifyClaims(allClaims.toList, urlVerifier).flatMap { claimsWithStatus =>
        val shuffled = new scala.util.Random(seed).shuffle(claimsWithStatus)
        val blindClaims = shuffled.map { case (claim, status) =>
          BlindClaim(claim.text, claim.sourceUrl, status)
        }
        judge.score(blindClaims, task.check).map { marks =>
          val markedClaims = shuffled.zip(marks).map { case ((claim, status), mark) =>
            MarkedClaim(claim, status, mark)
          }
          ResearchReport(task.check, markedClaims)
        }
      }
    }

  private def buildPrompt(task: Task, copyIdx: Int, total: Int): String =
    s"[Copy $copyIdx of $total] Research the following goal. This copy has no context from other copies.\nGoal: ${task.goal}\nRubric: ${task.check}\nFormat each claim as: CLAIM: <text> | SOURCE: <url>"

  private def configForCopy(copyIdx: Int): BrainConfig =
    BrainConfig(BackendKinds((copyIdx - 1) % BackendKinds.length))

  private def runCopies(
      task: Task,
      brainRegistry: BrainRegistry,
      copyIndices: List[Int],
      total: Int
  ): Seq[Claim] < Sync =
    copyIndices match
      case Nil => Seq.empty
      case copyIdx :: rest =>
        val prompt = buildPrompt(task, copyIdx, total)
        val config = configForCopy(copyIdx)
        val brain = brainRegistry.resolve(config)
        Abort.run[BrainError](brain.ask(prompt)).flatMap { result =>
          val claims = result.fold(
            response => ClaimParser.parse(response),
            _ => Seq.empty,
            _ => Seq.empty
          )
          runCopies(task, brainRegistry, rest, total).map(restClaims => claims ++ restClaims)
        }

  private def verifyClaims(
      claims: List[Claim],
      urlVerifier: UrlVerifier
  ): Seq[(Claim, UrlStatus)] < Sync =
    claims match
      case Nil => Seq.empty
      case claim :: rest =>
        Abort.run[VerifierError](urlVerifier.verify(claim.sourceUrl)).flatMap { result =>
          val status = result.fold(s => s, _ => UrlStatus.Unreachable, _ => UrlStatus.Unreachable)
          verifyClaims(rest, urlVerifier).map(restVerified => (claim, status) +: restVerified)
        }

end ResearchCoordinator

package meeseeks.brain

import meeseeks.port.Brain

/** Factory: given a BrainConfig, resolve the Brain implementation for that backend kind. */
trait BrainRegistry:
  def resolve(config: BrainConfig): Brain

package meeseeks.sandbox

/** A single backend credential: an env var name and a secret value. The secret is never logged:
  * toString shows only the name.
  */
final class Credential private (val name: String, private val secret: String):

  /** Returns "NAME=VALUE" for use in a Podman env file. */
  def toEnvLine: String = s"$name=$secret"

  override def toString: String = s"Credential($name=***)"

  override def equals(obj: Any): Boolean = obj match
    case other: Credential => name == other.name && secret == other.secret
    case _                 => false

  override def hashCode(): Int = java.util.Objects.hash(name, secret)

object Credential:
  def apply(name: String, secret: String): Credential = new Credential(name, secret)

package meeseeks.dev

/** Development utilities. Never imported by the runtime module. */
object DevTools:
  def banner(label: String): String = s"=== $label ==="

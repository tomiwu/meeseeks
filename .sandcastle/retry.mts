import { execFileSync } from "node:child_process";

export type Exec = (cmd: string, args: string[]) => string;

export const realExec: Exec = (cmd, args) =>
  execFileSync(cmd, args, { encoding: "utf8", timeout: 60_000, killSignal: "SIGKILL" }).trim();

export type Hooks = {
  exec?: Exec;
  log?: (line: string) => void;
  err?: (line: string) => void;
  exit?: (code: number) => never;
};

// Run a command. On timeout or error, log one line and retry once. Then throw.
export function retryOnce(cmd: string, args: string[], hooks: Hooks = {}): string {
  const exec = hooks.exec ?? realExec;
  const log = hooks.log ?? console.log;
  for (let i = 1; ; i++) {
    try { return exec(cmd, args); }
    catch (e) {
      if (i >= 2) throw e;
      log(`${cmd} ${args.slice(0, 2).join(" ")} failed or timed out, retry once`);
    }
  }
}

// Same as retryOnce, but after two failures log a BLOCKER line and exit with code 2.
export function retryOrBlock(cmd: string, args: string[], hooks: Hooks = {}): string {
  const err = hooks.err ?? console.error;
  const exit = hooks.exit ?? ((code: number) => process.exit(code));
  try { return retryOnce(cmd, args, hooks); }
  catch {
    err(`BLOCKER: ${cmd} ${args.slice(0, 2).join(" ")} failed or timed out 2 times. Loop stops.`);
    return exit(2);
  }
}

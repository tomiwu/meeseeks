# Meeseeks

A task bot. You give it a task with a goal and a check. A Meeseeks works until the check passes, sends one result, and stops.

The server runs on your host. You send tasks in Telegram. It keeps its state in an H2 file journal.

Stack: Scala 3.10.0, sbt 2.0.12, Apache Pekko (typed, persistence, cluster), Kyo, Podman, H2 file journal in `./data/journal`.

Docs: [docs/PRD.md](docs/PRD.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/RUNNING.md](docs/RUNNING.md).

## Quick start

You need JDK 25 (Temurin), sbt 2.0.12, Podman, and Docker Compose. See [docs/RUNNING.md](docs/RUNNING.md).

```sh
git clone https://github.com/tomiwu/meeseeks.git
cd meeseeks
cp .env.example .env     # ./setup.sh does this for you if .env is missing
# Edit .env: set MEESEEKS_TELEGRAM_BOT_TOKEN, MEESEEKS_OWNER_ID, MEESEEKS_TELEGRAM_CHAT_ID
./setup.sh
set -a; . ./.env; set +a # load .env into your shell
sbt "runtime/run"
```

Never commit `.env`. Git ignores it.

Then send one task to the bot in Telegram:

```text
build: add a function that reverses a string
check: sbt test
```

The bot replies `Task m-1 accepted.` When the check passes, it sends one result.

Send `kill` to stop all work. Send `resume` to start again.

## Fan project

This is a fan project inspired by the Mr. Meeseeks character from *Rick and Morty*. It is not affiliated with, endorsed by, or connected to Adult Swim, Warner Bros. Discovery, or the creators of *Rick and Morty*.

## License

MIT. See [LICENSE](LICENSE).

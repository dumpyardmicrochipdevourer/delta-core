# delta-core

Downloads video and audio by link: a job queue on top of yt-dlp, with gallery-dl
for posts that only have pictures. Serves the delta page and the Telegram bot.

Spring Boot 4, Java 21. No database: a job is a json file, the queue is a directory.

## How it works

One worker, one job at a time, so the whole disk budget goes to the job in flight.
A job is taken through a ladder of heights (1080 → 720 → 480 → 360 from the chosen
one down): some sites do not tell the size before the download, and stepping down
is simpler than guessing. While yt-dlp runs, a guard watches the size on disk,
the timeout and cancellation, because `--max-filesize` trusts what a site declares.
When YouTube asks to prove it is not talking to a bot, the job is retried through
the android client and that client is used for the next half an hour.

A post without a video falls back to its pictures. A playlist is downloaded item
by item and zipped on the way out, never on disk. A finished file waits to be
saved for `DELTA_RESULT_TTL` and is removed earlier if the budget is needed.

## Who can do what

| | anonymous | signed in (lambda-auth token) | bot (`X-Delta-Token`) |
|---|---|---|---|
| at once | 1 | 3 | 3 per user |
| per hour, per address / account | 6 | 30 | — |
| file | 512 MiB | 1.9 GB | 1.9 GB |
| playlists | no | up to 25 items | no |

Links to addresses instead of sites, to private networks and anything that is not
http(s) are refused: yt-dlp fetches whatever it is given, and the service lives
inside a private network.

## API

| method | path | what |
|---|---|---|
| POST | `/api/jobs` | `{url, mode, options?, items?}` → 201 job |
| GET | `/api/jobs/{id}` | state, progress, title, file name; the id is the only key |
| DELETE | `/api/jobs/{id}` | cancels a running job, frees the files of a finished one |
| GET | `/api/jobs/{id}/file` | the file (`Range` supported) or a zip for playlists and albums |
| GET | `/api/jobs/{id}/thumb` | preview, jpeg |
| POST | `/api/probe` | `{url}` → one video or a playlist with its entries |
| GET | `/api/limits` | what the caller is allowed |

`mode`: `video`, `audio`, `mute` (video without sound). `options`, each from a
closed list: `quality` 2160…360, `container` mp4/mkv/webm, `codec` h264/vp9/av1/any,
`audioFormat` mp3/m4a/opus, `bitrate` 320/256/192/128, `metadata`, `subtitles`.

States: `queued`, `running`, `done`, `failed`, `canceled`, `released`.
`error.code`: `unsupported`, `unavailable`, `live`, `too_large`, `blocked`, `failed`.
Refusals carry `code`: `busy`, `rate_limited`, `queue_full`, `login_required`, `bad_request`.

The bot sends `X-Delta-Token` and `X-Delta-User` and gets `paths` in the job:
absolute paths of the file, thumbnail, cover, photos and collage, which it hands
to a local Telegram Bot API server. For the bot a SoundCloud "video" is the sound
over its artwork, and an album also comes as one collage.

## Configuration

| variable | default | |
|---|---|---|
| `DELTA_PORT` / `DELTA_MANAGEMENT_PORT` | `8080` | health and metrics move to the second one |
| `DELTA_ROOT` | `./data` (`/data` in the image) | jobs, work |
| `DELTA_OUT` | `$DELTA_ROOT/out` | finished files; what the Bot API server mounts |
| `DELTA_BUDGET_BYTES` | 5 GiB | work + out together |
| `DELTA_RESULT_TTL` | `30m` | |
| `DELTA_BOT_TOKEN` | empty, bot API off | |
| `DELTA_AUTH_JWKS_URI`, `DELTA_AUTH_ISSUER` | lambda-auth on localhost | |
| `DELTA_SELF_UPDATE` | `false` (`true` in the image) | daily `yt-dlp -U` |

Limits per tier: `DELTA_ANON_*`, `DELTA_USER_*`, `DELTA_BOT_*`, see `application.yaml`.

## Run

```sh
./mvnw test          # tools are replaced by scripts in src/test/resources/fake-tools
docker build -t delta-core .
docker run -p 8080:8080 delta-core
```

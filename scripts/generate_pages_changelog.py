#!/usr/bin/env python3
"""Generate the GitHub Pages production changelog from repository history.

The repository remains the source of truth. No generated changelog file is
committed: the Pages workflow copies docs/ into a temporary site directory and
adds the generated changelog there.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterable
from zoneinfo import ZoneInfo


BERLIN = ZoneInfo("Europe/Berlin")
REFERENCE_RE = re.compile(r"#(\d+)")
EXPLICIT_TICKET_LINE_RE = re.compile(
    r"\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?|implement(?:s|ed)?)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Ticket:
    number: int
    title: str
    url: str


@dataclass(frozen=True)
class OtherChange:
    title: str
    url: str


@dataclass
class Promotion:
    sha: str
    promotion_id: str
    display_date: str
    introduced_commits: set[str]
    tickets: list[Ticket] = field(default_factory=list)
    other_changes: list[OtherChange] = field(default_factory=list)


class GitRepo:
    def __init__(self, workdir: Path) -> None:
        self.workdir = workdir

    def run(self, *args: str) -> str:
        completed = subprocess.run(
            ["git", *args],
            cwd=self.workdir,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return completed.stdout.strip()

    def is_ancestor(self, ancestor: str, descendant: str) -> bool:
        result = subprocess.run(
            ["git", "merge-base", "--is-ancestor", ancestor, descendant],
            cwd=self.workdir,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return result.returncode == 0

    def commit_parents(self, sha: str) -> list[str]:
        return self.run("show", "-s", "--format=%P", sha).split()

    def commit_time(self, sha: str) -> datetime:
        raw = self.run("show", "-s", "--format=%cI", sha)
        return datetime.fromisoformat(raw)

    def rev_list(self, range_spec: str) -> set[str]:
        raw = self.run("rev-list", range_spec)
        return {line for line in raw.splitlines() if line}


class GitHubApi:
    def __init__(self, repository: str, token: str, api_url: str) -> None:
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")
        self._issue_cache: dict[int, dict | None] = {}

    def _request_json(self, path: str, query: dict[str, str] | None = None):
        url = f"{self.api_url}{path}"
        if query:
            url += "?" + urllib.parse.urlencode(query)

        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "User-Agent": "regatta-tracker-pages-changelog",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))

    def list_closed_pulls(self, base: str) -> list[dict]:
        result: list[dict] = []
        page = 1
        while True:
            batch = self._request_json(
                f"/repos/{self.repository}/pulls",
                {
                    "state": "closed",
                    "base": base,
                    "sort": "updated",
                    "direction": "asc",
                    "per_page": "100",
                    "page": str(page),
                },
            )
            if not isinstance(batch, list):
                raise RuntimeError("GitHub pull request response is not a list")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1

    def issue(self, number: int) -> dict | None:
        if number in self._issue_cache:
            return self._issue_cache[number]

        try:
            value = self._request_json(
                f"/repos/{self.repository}/issues/{number}"
            )
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                value = None
            else:
                raise

        self._issue_cache[number] = value
        return value


def promotion_identity(timestamp: datetime) -> tuple[str, str]:
    local = timestamp.astimezone(BERLIN)
    return local.strftime("%y.%m.%d-%H%M"), local.strftime("%d.%m.%Y")


def extract_ticket_candidates(title: str, body: str | None) -> list[int]:
    explicit_numbers: list[int] = []

    for line in (body or "").splitlines():
        if EXPLICIT_TICKET_LINE_RE.search(line):
            explicit_numbers.extend(
                int(value) for value in REFERENCE_RE.findall(line)
            )

    numbers = explicit_numbers or [
        int(match.group(1))
        for match in REFERENCE_RE.finditer(title or "")
    ]

    seen: set[int] = set()
    return [
        number
        for number in numbers
        if not (number in seen or seen.add(number))
    ]


def is_ticket_issue(issue: dict | None) -> bool:
    return bool(issue) and "pull_request" not in issue


def resolve_pull_request(
    pull: dict,
    issue_loader: Callable[[int], dict | None],
) -> tuple[list[Ticket], OtherChange | None]:
    tickets: list[Ticket] = []

    for number in extract_ticket_candidates(
        str(pull.get("title") or ""),
        pull.get("body"),
    ):
        issue = issue_loader(number)
        if not is_ticket_issue(issue):
            continue
        tickets.append(
            Ticket(
                number=number,
                title=str(issue.get("title") or "").strip(),
                url=str(issue.get("html_url") or ""),
            )
        )

    if tickets:
        return tickets, None

    return [], OtherChange(
        title=str(pull.get("title") or f"PR #{pull.get('number', '?')}").strip(),
        url=str(pull.get("html_url") or ""),
    )


def load_promotions(
    api: GitHubApi,
    git: GitRepo,
    master_ref: str,
) -> list[Promotion]:
    promotions: list[Promotion] = []

    for pull in api.list_closed_pulls("master"):
        if not pull.get("merged_at"):
            continue
        if (pull.get("head") or {}).get("ref") != "staging":
            continue

        sha = str(pull.get("merge_commit_sha") or "")
        if not sha or not git.is_ancestor(sha, master_ref):
            continue

        parents = git.commit_parents(sha)
        timestamp = git.commit_time(sha)
        promotion_id, display_date = promotion_identity(timestamp)

        if len(parents) >= 2:
            introduced = git.rev_list(f"{parents[0]}..{parents[1]}")
        else:
            introduced = set()

        promotion = Promotion(
            sha=sha,
            promotion_id=promotion_id,
            display_date=display_date,
            introduced_commits=introduced,
        )

        if len(parents) < 2:
            promotion.other_changes.append(
                OtherChange(
                    title=str(pull.get("title") or "Production promotion"),
                    url=str(pull.get("html_url") or ""),
                )
            )

        promotions.append(promotion)

    promotions.sort(key=lambda item: git.commit_time(item.sha))
    return promotions


def map_dev_pulls_to_promotions(
    promotions: list[Promotion],
    pulls: Iterable[dict],
    issue_loader: Callable[[int], dict | None],
) -> None:
    for pull in pulls:
        if not pull.get("merged_at"):
            continue

        candidates = [
            str(pull.get("merge_commit_sha") or ""),
            str((pull.get("head") or {}).get("sha") or ""),
        ]
        candidates = [value for value in candidates if value]

        target = next(
            (
                promotion
                for promotion in promotions
                if any(value in promotion.introduced_commits for value in candidates)
            ),
            None,
        )
        if target is None:
            continue

        tickets, other = resolve_pull_request(pull, issue_loader)

        known_tickets = {ticket.number for ticket in target.tickets}
        target.tickets.extend(
            ticket for ticket in tickets if ticket.number not in known_tickets
        )

        if other is not None:
            known_other_urls = {item.url for item in target.other_changes}
            if other.url not in known_other_urls:
                target.other_changes.append(other)


def _link(url: str, text: str) -> str:
    escaped_text = html.escape(text)
    if not url:
        return escaped_text
    return f'<a href="{html.escape(url, quote=True)}">{escaped_text}</a>'


def _release_contents(promotion: Promotion) -> str:
    parts: list[str] = []

    if promotion.tickets:
        parts.append("<ul>")
        for ticket in sorted(promotion.tickets, key=lambda item: item.number):
            parts.append(
                "<li>"
                + _link(ticket.url, f"#{ticket.number} {ticket.title}")
                + "</li>"
            )
        parts.append("</ul>")

    if promotion.other_changes:
        parts.append("<h3>Sonstiges</h3>")
        parts.append("<ul>")
        for change in promotion.other_changes:
            parts.append("<li>" + _link(change.url, change.title) + "</li>")
        parts.append("</ul>")

    if not promotion.tickets and not promotion.other_changes:
        parts.append('<p class="muted">Keine Ticket-PRs zugeordnet.</p>')

    return "\n".join(parts)


def _document(title: str, body: str) -> str:
    return f"""<!doctype html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="Regatta Tracker Production Changelog">
  <title>{html.escape(title)}</title>
  <link rel="stylesheet" href="../assets/style.css">
</head>
<body>
  <main>
    <nav><a href="../">Regatta Tracker</a></nav>
{body}
  </main>
</body>
</html>
"""


def render_index(promotions: list[Promotion]) -> str:
    cards: list[str] = [
        '    <section class="card">',
        "      <h1>Production Changelog</h1>",
        '      <p class="muted">Automatisch aus den Production-Promotions und den zugehörigen GitHub-Tickets erzeugt.</p>',
        "    </section>",
    ]

    for promotion in reversed(promotions):
        page = html.escape(f"{promotion.promotion_id}.html", quote=True)
        cards.extend(
            [
                "    <br>",
                '    <section class="card">',
                f'      <h2><a href="{page}">{html.escape(promotion.display_date)} — {html.escape(promotion.promotion_id)}</a></h2>',
                _indent(_release_contents(promotion), 6),
                "    </section>",
            ]
        )

    return _document("Regatta Tracker Production Changelog", "\n".join(cards))


def render_promotion(promotion: Promotion) -> str:
    body = "\n".join(
        [
            '    <article>',
            f"      <h1>{html.escape(promotion.display_date)} — {html.escape(promotion.promotion_id)}</h1>",
            '      <p class="muted">Production-Promotion auf master. Die Kennung ist die Promotion-ID, nicht der Android-versionName.</p>',
            _indent(_release_contents(promotion), 6),
            '      <p><a href="./">Zur Changelog-Übersicht</a></p>',
            "    </article>",
        ]
    )
    return _document(
        f"Regatta Tracker {promotion.promotion_id}",
        body,
    )


def _indent(value: str, spaces: int) -> str:
    prefix = " " * spaces
    return "\n".join(prefix + line if line else line for line in value.splitlines())


def write_site(output: Path, promotions: list[Promotion]) -> None:
    output.mkdir(parents=True, exist_ok=True)
    (output / "index.html").write_text(render_index(promotions), encoding="utf-8")

    seen_ids: set[str] = set()
    for promotion in promotions:
        if promotion.promotion_id in seen_ids:
            raise RuntimeError(
                f"Duplicate production promotion id: {promotion.promotion_id}"
            )
        seen_ids.add(promotion.promotion_id)
        (output / f"{promotion.promotion_id}.html").write_text(
            render_promotion(promotion),
            encoding="utf-8",
        )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        help="GitHub repository in owner/name form",
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Output directory for generated changelog pages",
    )
    parser.add_argument(
        "--master-ref",
        default="HEAD",
        help="Git ref representing the deployed master history",
    )
    parser.add_argument(
        "--workdir",
        type=Path,
        default=Path("."),
        help="Checked-out repository root",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    token = os.environ.get("GITHUB_TOKEN", "")
    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")

    if not args.repository or "/" not in args.repository:
        raise RuntimeError("A valid --repository owner/name is required")
    if not token:
        raise RuntimeError("GITHUB_TOKEN is required")

    git = GitRepo(args.workdir)
    api = GitHubApi(args.repository, token, api_url)

    promotions = load_promotions(api, git, args.master_ref)
    dev_pulls = api.list_closed_pulls("dev")
    map_dev_pulls_to_promotions(promotions, dev_pulls, api.issue)
    write_site(args.output, promotions)

    print(
        f"Generated {len(promotions)} production promotion(s) in {args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

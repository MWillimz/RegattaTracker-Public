import unittest
from datetime import datetime, timezone

from generate_pages_changelog import (
    OtherChange,
    Promotion,
    Ticket,
    extract_ticket_candidates,
    map_dev_pulls_to_promotions,
    promotion_identity,
    render_index,
    resolve_pull_request,
)


class PromotionIdentityTests(unittest.TestCase):
    def test_uses_europe_berlin_for_promotion_id(self):
        promotion_id, display_date = promotion_identity(
            datetime(2026, 9, 20, 12, 15, tzinfo=timezone.utc)
        )

        self.assertEqual("26.09.20-1415", promotion_id)
        self.assertEqual("20.09.2026", display_date)


class TicketExtractionTests(unittest.TestCase):
    def test_title_and_explicit_body_references_are_candidates(self):
        self.assertEqual(
            [147, 154, 155],
            extract_ticket_candidates(
                "Implement #147 participant RET",
                "Related: #88\nCloses #154 and #155",
            ),
        )

    def test_unrelated_body_reference_is_not_candidate(self):
        self.assertEqual(
            [],
            extract_ticket_candidates(
                "Refresh documentation",
                "Related: #73",
            ),
        )

    def test_reference_to_pull_request_falls_back_to_sonstiges(self):
        pull = {
            "number": 152,
            "title": "Fix #151 review regression",
            "body": "",
            "html_url": "https://example.test/pull/152",
        }

        tickets, other = resolve_pull_request(
            pull,
            lambda number: {
                "number": number,
                "title": "Promotion PR",
                "html_url": "https://example.test/pull/151",
                "pull_request": {},
            },
        )

        self.assertEqual([], tickets)
        self.assertEqual("Fix #151 review regression", other.title)


class PromotionMappingTests(unittest.TestCase):
    def test_maps_dev_pr_to_release_and_deduplicates_ticket(self):
        promotions = [
            Promotion(
                sha="promotion-a",
                promotion_id="26.09.20-1415",
                display_date="20.09.2026",
                introduced_commits={"merge-a", "head-a"},
            )
        ]
        pulls = [
            {
                "number": 160,
                "title": "Implement participant RET (#147)",
                "body": "Closes #147",
                "merged_at": "2026-09-20T12:00:00Z",
                "merge_commit_sha": "merge-a",
                "head": {"sha": "head-a"},
                "html_url": "https://example.test/pull/160",
            },
            {
                "number": 162,
                "title": "Follow-up for #147",
                "body": "Fixes #147",
                "merged_at": "2026-09-20T12:05:00Z",
                "merge_commit_sha": "head-a",
                "head": {"sha": "unused"},
                "html_url": "https://example.test/pull/162",
            },
        ]

        def issue_loader(number):
            return {
                "number": number,
                "title": "RET button analog zu server funktionialität einfügen",
                "html_url": "https://example.test/issues/147",
            }

        map_dev_pulls_to_promotions(promotions, pulls, issue_loader)

        self.assertEqual([147], [ticket.number for ticket in promotions[0].tickets])
        self.assertEqual([], promotions[0].other_changes)

    def test_pr_without_ticket_is_sonstiges(self):
        promotions = [
            Promotion(
                sha="promotion-a",
                promotion_id="26.09.20-1415",
                display_date="20.09.2026",
                introduced_commits={"merge-a"},
            )
        ]
        pulls = [
            {
                "number": 149,
                "title": "Refresh README for current Regatta Tracker",
                "body": "",
                "merged_at": "2026-09-20T12:00:00Z",
                "merge_commit_sha": "merge-a",
                "head": {"sha": "head-a"},
                "html_url": "https://example.test/pull/149",
            }
        ]

        map_dev_pulls_to_promotions(promotions, pulls, lambda _: None)

        self.assertEqual([], promotions[0].tickets)
        self.assertEqual(
            ["Refresh README for current Regatta Tracker"],
            [change.title for change in promotions[0].other_changes],
        )


class HtmlRenderingTests(unittest.TestCase):
    def test_renders_only_ticket_title_and_sonstiges_text(self):
        promotion = Promotion(
            sha="promotion-a",
            promotion_id="26.09.20-1415",
            display_date="20.09.2026",
            introduced_commits=set(),
            tickets=[
                Ticket(
                    147,
                    "RET <tracking>",
                    "https://example.test/issues/147",
                )
            ],
            other_changes=[
                OtherChange(
                    "Refresh README",
                    "https://example.test/pull/149",
                )
            ],
        )

        result = render_index([promotion])

        self.assertIn("#147 RET &lt;tracking&gt;", result)
        self.assertIn("Sonstiges", result)
        self.assertIn("Refresh README", result)
        self.assertNotIn("<tracking>", result)


if __name__ == "__main__":
    unittest.main()

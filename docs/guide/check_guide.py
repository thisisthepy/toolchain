#!/usr/bin/env python3
"""The test for the guide site.

Run from anywhere:  python3 docs/guide/check_guide.py

It fails (exit 1) when any of these is true:

1. An HTML file does not parse, or a non-void element is left unclosed / closed out of order.
2. A relative href/src points at a file that does not exist, or a `#fragment` names an id the
   target page does not have.
3. A visible string exists in one language only:
   - every non-whitespace text node must sit inside an element carrying `data-lang="en"` or
     `data-lang="ko"`, unless it is inside code/pre/kbd/script/style or an element marked
     `translate="no"` (brand names, identifiers), or it contains no letters at all (`01`, `→`);
   - inside any parent, the number of direct `data-lang="en"` children must equal the number of
     direct `data-lang="ko"` children;
   - `<html>` must carry both `data-title-en` and `data-title-ko`;
   - any `aria-label` must be backed by `data-label-en` and `data-label-ko`.
4. A page is missing from the navigation, or the navigation links a page that does not exist.

What this check cannot catch: a Korean string that is a poor or wrong translation of its English
twin, and layout problems (overflow at 360 px). Those need a human looking at the page.
"""
from __future__ import annotations

import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source",
        "track", "wbr", "path", "circle", "rect", "line", "polyline", "polygon", "ellipse", "stop",
        "use"}
EXEMPT_TEXT = {"code", "pre", "kbd", "script", "style", "samp"}
EXPECTED_PAGES = {
    "index.html", "getting-started.html", "concepts.html", "guide-variants.html",
    "guide-dependencies.html", "guide-staging.html", "guide-tcl.html", "ecosystem.html",
    "status.html",
}


class Page(HTMLParser):
    def __init__(self, path: Path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.stack: list[tuple[str, dict]] = []
        # per open element: [en_children, ko_children]
        self.counts: list[list[int]] = []
        self.errors: list[str] = []
        self.ids: set[str] = set()
        self.links: list[str] = []
        self.nav_links: list[str] = []
        self.html_attrs: dict = {}

    def err(self, msg: str) -> None:
        line, _ = self.getpos()
        self.errors.append(f"{self.path.name}:{line}: {msg}")

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "html":
            self.html_attrs = a
        if "id" in a:
            if a["id"] in self.ids:
                self.err(f"duplicate id '{a['id']}'")
            self.ids.add(a["id"])
        for key in ("href", "src"):
            if key in a and a[key]:
                self.links.append(a[key])
                if key == "href" and any(t == "nav" for t, _ in self.stack):
                    self.nav_links.append(a[key])
        if "aria-label" in a and not ("data-label-en" in a and "data-label-ko" in a):
            self.err(f"<{tag} aria-label> without data-label-en/data-label-ko")
        lang = a.get("data-lang")
        if lang is not None:
            if lang not in ("en", "ko"):
                self.err(f"unknown data-lang '{lang}'")
            elif self.counts:
                self.counts[-1][0 if lang == "en" else 1] += 1
        if tag in VOID:
            return
        self.stack.append((tag, a))
        self.counts.append([0, 0])

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if not self.stack:
            self.err(f"stray </{tag}>")
            return
        open_tag, attrs = self.stack[-1]
        if open_tag != tag:
            self.err(f"</{tag}> closes <{open_tag}>")
            # recover: pop until match if present
            if any(t == tag for t, _ in self.stack):
                while self.stack and self.stack[-1][0] != tag:
                    self.stack.pop()
                    self.counts.pop()
            else:
                return
        self.stack.pop()
        en, ko = self.counts.pop()
        if en != ko:
            self.err(f"<{tag}> has {en} English child(ren) but {ko} Korean")

    def handle_data(self, data):
        if not any(ch.isalpha() for ch in data):
            return  # whitespace, digits, arrows and punctuation read the same in both languages
        for t, a in self.stack:
            if t in EXEMPT_TEXT or a.get("translate") == "no" or "data-lang" in a or t == "title":
                return
        self.err(f"text outside a data-lang element: {data.strip()[:60]!r}")

    def close(self):
        super().close()
        for t, _ in self.stack:
            self.errors.append(f"{self.path.name}: <{t}> never closed")


def main() -> int:
    pages = {p.name: p for p in sorted(ROOT.glob("*.html"))}
    errors: list[str] = []
    parsed: dict[str, Page] = {}

    missing = EXPECTED_PAGES - pages.keys()
    for name in sorted(missing):
        errors.append(f"expected page {name} does not exist")

    for name, path in pages.items():
        page = Page(path)
        page.feed(path.read_text(encoding="utf-8"))
        page.close()
        parsed[name] = page
        errors.extend(page.errors)
        for key in ("data-title-en", "data-title-ko"):
            if not page.html_attrs.get(key):
                errors.append(f"{name}: <html> lacks {key}")

    for name, page in parsed.items():
        for link in page.links:
            u = urlparse(link)
            if u.scheme in ("http", "https", "mailto", "data"):
                continue
            target_name = name if not u.path else u.path
            target = (ROOT / target_name).resolve()
            if not target.exists():
                errors.append(f"{name}: link to missing file '{link}'")
                continue
            if u.fragment and target.suffix == ".html":
                ids = parsed[target.name].ids if target.name in parsed else set()
                if u.fragment not in ids:
                    errors.append(f"{name}: link '{link}' names a missing id")
        navigated = {urlparse(l).path for l in page.nav_links if urlparse(l).path}
        for expected in EXPECTED_PAGES:
            if expected in pages and expected not in navigated:
                errors.append(f"{name}: navigation does not link {expected}")

    if not pages:
        errors.append("no HTML pages found")

    for e in errors:
        print(e)
    print(f"{len(pages)} page(s) checked, {len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

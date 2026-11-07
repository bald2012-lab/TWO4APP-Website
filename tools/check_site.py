#!/usr/bin/env python3
"""Static acceptance checks for the unpublished TWO4APP website."""
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import urlsplit
import re
import sys

ROOT=Path(__file__).resolve().parent.parent
class LinkParser(HTMLParser):
    def __init__(self):
        super().__init__();self.links=[];self.resources=[];self.h1=0;self.lang=None;self.title=False
    def handle_starttag(self, tag, attrs):
        d=dict(attrs)
        if tag=='html':self.lang=d.get('lang')
        if tag=='h1':self.h1+=1
        if tag=='title':self.title=True
        if tag in ('a','link') and 'href' in d:self.links.append(d['href'])
        if tag in ('script','iframe','img') and 'src' in d:self.resources.append(d['src'])
errors=[];pages=list(ROOT.rglob('*.html'))
for file in pages:
    raw=file.read_text('utf-8');p=LinkParser();p.feed(raw)
    if p.h1!=1:errors.append(f'{file.relative_to(ROOT)}: {p.h1} h1 headings')
    if p.lang not in ('de','en','ru'):errors.append(f'{file.relative_to(ROOT)}: lang={p.lang}')
    if not p.title:errors.append(f'{file.relative_to(ROOT)}: no title')
    for href in p.links:
        x=urlsplit(href)
        if x.scheme in ('https','http','mailto','tel') or href.startswith('#'):continue
        ref=(ROOT/x.path.lstrip('/') if x.path.startswith('/') else file.parent/x.path).resolve()
        if ref.is_dir():ref=ref/'index.html'
        if not ref.is_file():errors.append(f'{file.relative_to(ROOT)}: broken href={href}')
        if x.fragment:
            dst=ref.read_text('utf-8')
            if f'id="{x.fragment}"' not in dst: errors.append(f'{file.relative_to(ROOT)}: missing anchor={href}')
    for src in p.resources:
        if src.startswith(('http:','https:','//')):errors.append(f'{file.relative_to(ROOT)}: external script/image {src}')
for expected in ['index.html','en/index.html','de/impressum.html','de/datenschutz.html',
  'de/datenschutz-vergissmeinnicht.html','en/privacy-vergissmeinnicht.html',
  'privacy/vergissmeinnicht.html','assets/style.css','assets/favicon.svg','robots.txt']:
    if not (ROOT/expected).is_file():errors.append('Missing required path: '+expected)
css=(ROOT/'assets/style.css').read_text('utf-8')
if css.count('{')!=css.count('}'):errors.append('Unbalanced CSS rule braces')
if re.search(r'@import\b|url\(\s*[\'\"]?https?://',css,re.I):errors.append('External CSS dependency')
if not all(x in (ROOT/'privacy/vergissmeinnicht.html').read_text('utf-8') for x in
  ['<h2>Datenschutzerklärung — Vergissmeinnicht</h2>','<h2>Privacy policy — Vergissmeinnicht</h2>']):
    errors.append('Bilingual stable privacy content missing')
if '--legal-report' in sys.argv:
    legal_markers=['[VOLLSTÄNDIGER','[POSTANSCHRIFT','[DATENSCHUTZ','[VERBINDLICHE','[FULL LEGAL NAME','[POSTAL ADDRESS','[PRIVACY EMAIL','[SUPPORT-E-MAIL','[NAME DES','[SUPPORT EMAIL']
    marked=[]
    for p in pages:
        raw=p.read_text('utf-8')
        if any(m in raw for m in legal_markers):marked.append(str(p.relative_to(ROOT)))
    print(f'PUBLICATION BLOCKED — {len(marked)} files still contain legally required placeholders. Site is a draft.')
    print('Files to review: '+', '.join(marked))
assert not errors,'\n'.join(errors)
print(f'PASS {len(pages)} HTML pages; all local links and language targets resolve; no remote site assets; valid core structure.')

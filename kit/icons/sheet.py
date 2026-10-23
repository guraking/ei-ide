"""아이콘 SVG 를 out/ 에 쓰고, 밝게 · 어둡게 견본 시트 HTML 을 만든다."""
import sys, os, html, importlib
sys.path.insert(0, os.path.dirname(__file__))
from lib import svg
out = sys.argv[1]; os.makedirs(out + '/svg', exist_ok=True)
sections = []
for mod, title in [('defs_own', None)] + [(m, None) for m in sys.argv[2:]]:
    d = importlib.import_module(mod)
    for sec, names in d.SECTIONS:
        sections.append((sec, [(n, d.I[n]) for n in names]))
def cell(name, body, desc):
    l, d = svg(body, False), svg(body, True)
    two = lambda s: s.replace("<svg ", "<svg class=x2 ") + s
    return f'<div class="c"><div class="ic"><span class=lt>{two(l)}</span><span class=dk>{two(d)}</span></div><div class="n">{html.escape(name)}</div><div class="d">{html.escape(desc)}</div></div>'
parts = []
for sec, items in sections:
    for n, (b, d) in items:
        open(f'{out}/svg/{n}.svg', 'w').write(svg(b, False)); open(f'{out}/svg/{n}_dark.svg', 'w').write(svg(b, True))
    parts.append(f'<section><h2>{html.escape(sec)} ({len(items)})</h2><div class="g">' + ''.join(cell(n, b, d) for n, (b, d) in items) + '</div></section>')
open(f'{out}/sheet.html', 'w').write('''<!doctype html><meta charset=utf-8><style>
body{margin:0;font:12px Inter,"Noto Sans KR",sans-serif;background:#888}
section{padding:14px 18px;background:#F7F8FA;color:#1E1F22} .lt{background:#fff} .dk{background:#1E1E22}
h2{font-size:14px;margin:0 0 10px} .g{display:grid;grid-template-columns:repeat(7,1fr);gap:8px}
.c{display:flex;flex-direction:column;align-items:center;gap:3px;padding:6px;border-radius:6px}
.c{background:#fff;border:1px solid #EBECF0}
.ic{display:flex;gap:4px}.ic span{display:flex;align-items:center;gap:8px;padding:6px 8px;border-radius:5px;border:1px solid #EBECF0}.x2{width:32px;height:32px}
.n{font:600 11px "JetBrains Mono",monospace}.d{font-size:10.5px;opacity:.75;text-align:center}
</style>''' + ''.join(parts))
print(sum(len(i) for _, i in sections), 'icons')

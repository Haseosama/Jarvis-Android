#!/usr/bin/env python3
"""Builds the dependency graph of the Kotlin code and writes graph.json, GRAPH_REPORT.md and graph.html.

Usage: python3 .claude/skills/graphify/graphify.py [repo root] [output dir]
Links are inferred from the types a file names that another file declares (comments and strings removed),
so they are an approximation: a type named like another module's type can create a false link.
"""
import collections
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else '.')
OUT = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, 'graphify-out'))
MAIN = 'app/src/main/java'
TEST = 'app/src/test/java'
BASE = 'com.jarvis.android'
ROOT_MODULE = '(racine)'

DECL = re.compile(r'^(?!\s*private)(?:[a-z ]*?)(?:class|object|interface|enum class|data class|sealed class|fun interface)\s+([A-Z]\w*)', re.M)


def strip(src):
    src = re.sub(r'/\*.*?\*/|//[^\n]*', '', src, flags=re.S)
    return re.sub(r'""".*?"""|"(?:\\.|[^"\\\n])*"', '""', src, flags=re.S)


def module(pkg):
    rest = pkg[len(BASE):].lstrip('.') if pkg.startswith(BASE) else pkg
    return rest.split('.')[0] or ROOT_MODULE


def kotlin_files(top):
    for d, _, fs in os.walk(top):
        for f in sorted(fs):
            if f.endswith('.kt'):
                yield os.path.join(d, f)


def package(src):
    m = re.search(r'^package\s+([\w.]+)', src, re.M)
    return m.group(1) if m else ''


def main():
    files = {}
    for p in kotlin_files(os.path.join(ROOT, MAIN)):
        raw = open(p, encoding='utf-8').read()
        src = strip(raw)
        files[p] = dict(pkg=package(raw), src=src, decls=set(DECL.findall(src)),
                        lines=raw.count('\n') + 1, name=os.path.basename(p)[:-3])
    owner = {}
    for p, info in files.items():
        for d in info['decls']:
            owner.setdefault(d, p)

    fedges, medges = set(), collections.Counter()
    for p, info in files.items():
        for tok in set(re.findall(r'\b([A-Z]\w+)\b', info['src'])):
            q = owner.get(tok)
            if q and q != p and tok not in info['decls']:
                fedges.add((p, q))
                a, b = module(info['pkg']), module(files[q]['pkg'])
                if a != b:
                    medges[(a, b)] += 1

    mods = collections.defaultdict(lambda: dict(files=0, lines=0))
    for info in files.values():
        m = mods[module(info['pkg'])]
        m['files'] += 1
        m['lines'] += info['lines']
    tests = collections.Counter(module(package(open(p, encoding='utf-8').read()))
                                for p in kotlin_files(os.path.join(ROOT, TEST)))
    indeg = collections.Counter(q for _, q in fedges)
    outdeg = collections.Counter(p for p, _ in fedges)
    rel = lambda p: os.path.relpath(p, os.path.join(ROOT, MAIN))

    data = dict(
        modules=[dict(id=k, **v, tests=tests.get(k, 0)) for k, v in sorted(mods.items())],
        medges=[dict(s=a, t=b, w=w) for (a, b), w in sorted(medges.items())],
        files=[dict(id=rel(p), name=i['name'], mod=module(i['pkg']), lines=i['lines'],
                    inn=indeg[p], out=outdeg[p]) for p, i in files.items()],
        fedges=sorted([rel(a), rel(b)] for a, b in fedges))

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'graph.json'), 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False)
    page = open(os.path.join(HERE, 'template.html'), encoding='utf-8').read()
    page = page.replace('__SRC__', MAIN).replace('__DATA__', json.dumps(data, ensure_ascii=False))
    with open(os.path.join(OUT, 'graph.html'), 'w', encoding='utf-8') as f:
        f.write(page)

    total = sum(m['lines'] for m in mods.values())
    mutual = sorted({tuple(sorted(e)) for e in medges if (e[1], e[0]) in medges and ROOT_MODULE not in e})
    name = lambda p: files[p]['name']
    report = [
        '# Carte du code Jarvis', '',
        f'{len(files)} fichiers, {total} lignes, {len(mods)} modules, {len(fedges)} liens entre fichiers, '
        f'{sum(tests.values())} fichiers de test.', '',
        '## Fichiers les plus référencés', '',
        *[f'- `{name(p)}` : utilisé par {n} fichiers' for p, n in indeg.most_common(12)], '',
        '## Fichiers qui dépendent du plus de fichiers', '',
        *[f'- `{name(p)}` : {n} fichiers' for p, n in outdeg.most_common(8)], '',
        '## Modules par taille', '',
        '| Module | Fichiers | Lignes | Tests |', '|---|---:|---:|---:|',
        *[f'| {k} | {v["files"]} | {v["lines"]} | {tests.get(k, 0)} |'
          for k, v in sorted(mods.items(), key=lambda kv: -kv[1]['lines'])], '',
        '## Modules sans test', '',
        ', '.join(f'`{k}`' for k in sorted(mods) if not tests.get(k)) or 'Aucun.', '',
        f'## Dépendances circulaires entre modules ({len(mutual)}, hors racine)', '',
        *[f'- `{a}` ↔ `{b}`' for a, b in mutual], '',
    ]
    with open(os.path.join(OUT, 'GRAPH_REPORT.md'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(report))
    print(f'{len(files)} fichiers, {len(fedges)} liens, {len(mods)} modules -> {OUT}')


if __name__ == '__main__':
    main()

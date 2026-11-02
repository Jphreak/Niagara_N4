#!/usr/bin/env python3
"""
sync_common.py - keep every Program Object's copy of the Common helpers
identical to Common_N4.java / Common_AX.java.

  python3 sync_common.py check            report drift for every program in programs.json
  python3 sync_common.py apply <file>     copy the Common helpers into one program
  python3 sync_common.py apply --all      ... into every program in programs.json

"apply" replaces any method with a Common name by the Common version (with
its comment block), adds the methods of the program's tiers that are
missing, and adds the Common fields. Program files are Program Object
bodies (no class wrapper); a file whose last line is "}" (a module class)
gets new methods before that brace.
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
TIER_MARK = re.compile(r'^// =+ (FIELDS|CORE|FILES|CSV|ORD|REVERSE|LINKS) =+$', re.M)


def method_span(src, name):
    """(start, end) of method `name` incl. the comment lines directly above it."""
    m = re.search(r'(?m)^[ \t]*(?:private|public|protected)[^\n;=]*\b' + re.escape(name) + r'\s*\(', src)
    if not m:
        return None
    i = src.index('{', m.end())
    depth = 0
    for j in range(i, len(src)):
        if src[j] == '{':
            depth += 1
        elif src[j] == '}':
            depth -= 1
            if depth == 0:
                end = j + 1
                break
    start = m.start()
    # walk back over contiguous // comment lines
    while True:
        prev_nl = src.rfind('\n', 0, start - 1)
        line = src[prev_nl + 1:start - 1] if start > 0 else ''
        if start > 0 and line.lstrip().startswith('//') and not TIER_MARK.match(line.strip()):
            start = prev_nl + 1
        else:
            break
    return start, end


def param_count(src, span):
    body = src[span[0]:span[1]]
    m = re.search(r'(?m)^(?:private|public|protected)[^\n;=]*?\w+\s*\(([^)]*)\)', body)
    p = m.group(1).strip() if m else ''
    return 0 if not p else p.count(',') + 1


def load_common(platform):
    src = open(os.path.join(HERE, 'Common_%s.java' % platform), encoding='utf-8').read()
    tiers, fields = {}, []
    marks = list(TIER_MARK.finditer(src))
    for k, mk in enumerate(marks):
        body = src[mk.end(): marks[k + 1].start() if k + 1 < len(marks) else len(src)]
        tier = mk.group(1).lower()
        if tier == 'fields':
            fields = re.findall(r'(?m)^private [^(\n]*;$', body)
            continue
        names = re.findall(r'(?m)^(?:private|public|protected)[^\n;=(]*?\b(\w+)\s*\(', body)
        tiers[tier] = []
        for n in names:
            span = method_span(body, n)
            tiers[tier].append((n, body[span[0]:span[1]]))
    return tiers, fields


def norm(s):
    s = re.sub(r'//[^\n]*', '', s)
    return re.sub(r'\s+', ' ', s).strip()


def wanted(tiers, program):
    out = []
    for t in program['tiers']:
        out += tiers[t]
    return out


def apply(path, program):
    tiers, fields = load_common(program['platform'])
    src = open(path, encoding='utf-8').read()
    report = []
    all_common = dict((n, code) for t in tiers.values() for (n, code) in t)
    want = dict(wanted(tiers, program))
    # replace existing
    for name, code in all_common.items():
        span = method_span(src, name)
        if not span:
            continue
        if norm(src[span[0]:span[1]]) != norm(code):
            pc_old = param_count(src, span)
            pc_new = param_count(code, (0, len(code)))
            note = '' if pc_old == pc_new else '  (params %d -> %d: fix callers)' % (pc_old, pc_new)
            report.append('replaced ' + name + note)
            src = src[:span[0]] + code + src[span[1]:]
    # add missing
    add = [code for name, code in want.items() if not method_span(src, name)]
    # a field is added only when the program's code uses it and doesn't declare it
    pending = src + '\n'.join(add)
    add_fields = []
    for f in fields:
        fname = f.split('=')[0].strip().split()[-1]
        declared = re.search(r'(?m)^\s*private\s+[\w.]+\s+' + fname + r'\s*[=;]', src)
        used = re.search(r'\b' + fname + r'\b', pending)
        if used and not declared:
            add_fields.append(f)
    if add or add_fields:
        block = '\n// ============================================ COMMON (sync_common.py) ===\n'
        if add_fields:
            block += '\n'.join(add_fields) + '\n\n'
        block += '\n\n'.join(add) + '\n'
        stripped = src.rstrip()
        if stripped.endswith('}') and program.get('kind') == 'module':
            cut = stripped.rfind('}')
            src = stripped[:cut] + block + '}\n'
        else:
            src = stripped + '\n' + block
        report += ['added ' + n for n, c in want.items() if c in add]
        report += ['added field ' + f.strip() for f in add_fields]
    open(path, 'w', encoding='utf-8').write(src)
    return report


def check(program, base):
    tiers, fields = load_common(program['platform'])
    src = open(os.path.join(base, program['path']), encoding='utf-8').read()
    problems = []
    want = dict(wanted(tiers, program))
    for name, code in [(n, c) for t in tiers.values() for (n, c) in t]:
        span = method_span(src, name)
        if span is None:
            if name in want:
                problems.append('missing ' + name)
        elif norm(src[span[0]:span[1]]) != norm(code):
            problems.append('differs ' + name)
    return problems + version_problems(program['path'], src)


def version_problems(path, src):
    """Header Version:, VERSION constant, file name and quickGuide title must agree."""
    out = []
    m = re.search(r'\bString\s+VERSION\s*=\s*"([^"]+)"', src)
    if not m:
        return ['no VERSION constant']
    ver = m.group(1)
    h = re.search(r'^\s*\*?\s*Version:\s*(\S+)', src, re.M)
    if h and h.group(1) != ver:
        out.append('header Version: %s but VERSION = %s' % (h.group(1), ver))
    f = re.search(r'_v?(\d[\w.]*?)\.java$', os.path.basename(path))
    core = re.sub(r'^v', '', re.sub(r'-(AX|m)$', '', ver))
    if f and f.group(1) != core:
        out.append('file name says %s but VERSION = %s' % (f.group(1), ver))
    q = re.search(r'QUICK_GUIDE\s*=\s*\n?\s*"([^"]*)"\s*\+\s*("[^"]*"|\w+)', src)
    if q and (re.search(r'\bv?\d+\.\d+', q.group(1)) or q.group(2).startswith('"')):
        out.append('quickGuide title hard-codes a version - use VERSION')
    return out


def main():
    progs = json.load(open(os.path.join(HERE, 'programs.json')))
    base = os.path.abspath(os.path.join(HERE, '..', '..'))
    if len(sys.argv) >= 2 and sys.argv[1] == 'check':
        bad = 0
        for p in progs:
            pr = check(p, base)
            print(('OK    ' if not pr else 'DRIFT ') + p['path'])
            for x in pr:
                print('        ' + x)
            bad += bool(pr)
        sys.exit(1 if bad else 0)
    if len(sys.argv) >= 3 and sys.argv[1] == 'apply':
        targets = progs if sys.argv[2] == '--all' else \
            [p for p in progs if os.path.abspath(os.path.join(base, p['path'])) == os.path.abspath(sys.argv[2])]
        if not targets:
            sys.exit('not in programs.json: ' + sys.argv[2])
        for p in targets:
            print(p['path'])
            for line in apply(os.path.join(base, p['path']), p):
                print('   ' + line)
        return
    print(__doc__)


if __name__ == '__main__':
    main()

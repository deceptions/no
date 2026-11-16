#!/usr/bin/env python3
"""Regenerates THIRD_PARTY_LICENSES.md and app/src/main/assets/licenses.txt from the resolved runtime classpath.
Usage: ./gradlew :app:dependencies --configuration phoneReleaseRuntimeClasspath > deps.txt ; python scripts/gen_licenses.py deps.txt"""
import re, sys, os, glob
import xml.etree.ElementTree as ET

GRADLE = os.path.expanduser('~/.gradle/caches/modules-2/files-2.1')
# Entries whose POM carries no license (checked manually against the upstream repository)
OVERRIDES = {
    'com.github.Blatzar:NiceHttp': 'GPL-3.0',
    'com.github.teamnewpipe:NewPipeExtractor': 'GPL-3.0-or-later',
    'com.github.teamnewpipe:nanojson': 'Apache-2.0',
    'com.github.TeamNewPipe.NewPipeExtractor:extractor': 'GPL-3.0-or-later',
    'com.github.TeamNewPipe.NewPipeExtractor:timeago-parser': 'GPL-3.0-or-later',
    'com.github.TeamNewPipe:nanojson': 'Apache-2.0',
    'com.jaredrummler:cyanea': 'Apache-2.0',
    'uy.kohesive.injekt:injekt-core': 'MIT',
    'org.mozilla:rhino': 'MPL-2.0',
    'io.reactivex:rxjava': 'Apache-2.0',
    'org.jsoup:jsoup': 'MIT',
}
PREFIX = [
    ('androidx.', 'Apache-2.0'), ('com.google.android.exoplayer', 'Apache-2.0'), ('com.google.android.material', 'Apache-2.0'),
    ('com.google.android.gms', 'Android Software Development Kit License (Google Play services, proprietary)'),
    ('com.google.android.datatransport', 'Apache-2.0'), ('com.google.firebase', 'Apache-2.0'), ('com.google.guava', 'Apache-2.0'),
    ('com.google.auto', 'Apache-2.0'), ('com.google.errorprone', 'Apache-2.0'), ('com.google.j2objc', 'Apache-2.0'),
    ('com.google.code.findbugs', 'Apache-2.0'), ('com.fasterxml.jackson', 'Apache-2.0'), ('com.github.bumptech.glide', 'BSD-2-Clause, MIT, Apache-2.0'),
    ('com.squareup.moshi', 'Apache-2.0'), ('ch.acra', 'Apache-2.0'), ('javax.inject', 'Apache-2.0'), ('org.checkerframework', 'MIT'),
    ('org.jetbrains', 'Apache-2.0'), ('com.jaredrummler', 'Apache-2.0'), ('jp.wasabeef', 'Apache-2.0'), ('com.github.discord', 'Apache-2.0'),
]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def pom_path(g, a, v):
    hits = glob.glob(f'{GRADLE}/{g}/{a}/{v}/*/*.pom')
    return hits[0] if hits else None

def licenses(g, a, v, depth=0):
    p = pom_path(g, a, v)
    if not p or depth > 4:
        return []
    try:
        root = ET.parse(p).getroot()
    except ET.ParseError:
        return []
    names = [(l.findtext('m:name', namespaces=NS) or '').strip() for l in root.findall('.//m:licenses/m:license', NS)]
    names = [n for n in names if n]
    if names:
        return names
    par = root.find('m:parent', NS)
    if par is not None:
        pg, pa, pv = (par.findtext(f'm:{k}', namespaces=NS) for k in ('groupId', 'artifactId', 'version'))
        return licenses(pg, pa, pv, depth + 1)
    return []

def norm(n):
    n = n.replace('The Apache Software License, Version 2.0', 'Apache-2.0').replace('The Apache License, Version 2.0', 'Apache-2.0')
    n = n.replace('Apache License, Version 2.0', 'Apache-2.0').replace('Apache 2.0', 'Apache-2.0').replace('Apache License 2.0', 'Apache-2.0')
    n = n.replace('The MIT License', 'MIT').replace('MIT License', 'MIT')
    return n

deps = {}
for line in open(sys.argv[1], encoding='utf-8', errors='ignore'):
    m = re.search(r'(?:\+|\\)--- ([\w.\-]+):([\w.\-]+):([^\s]+?)(?: -> ([^\s]+))?(?: \(\*\)| \(c\))?$', line.strip())
    if m:
        g, a, v, nv = m.groups()
        deps[(g, a)] = nv or v.strip('{}').split('!!')[0]

EXTRA = '''## Components that are not Maven dependencies

| Component | License | Notes |
|---|---|---|
| Original Shiro app (Blatzar / LagradOst) | MIT | Nekomo is a fork; the MIT notice in `LICENSE` must be kept |
| `app/libs/cs3api.jar` (CloudStream API, recloudstream/cloudstream) | GPL-3.0 | Lets CloudStream `.cs3` plugins run. Makes the combined app GPL-3.0 |
| `eu.kanade.tachiyomi.*`, `tachiyomi.*` (extension API) | Apache-2.0 | From aniyomiorg/aniyomi and mihonapp/mihon, copied via the GPL-3.0 Zangetsu project |
| Zangetsu JS host API design (Spyou/zangetsu) | GPL-3.0 | `utils/zg/*` implements the same host API |
| `com.nirhart.parallaxscroll` (vendored) | Apache-2.0 | Original JitPack artifact is gone |

## Licensing of the combined app

Nekomo's own code started as MIT (see `LICENSE`). Because the shipped APK includes GPL-3.0 components
(the CloudStream API, NiceHttp, NewPipeExtractor, Zangetsu-derived code), **the distributed APK as a whole must be
offered under GPL-3.0-or-later** (`COPYING`), with the complete corresponding source available (this repository).
MIT-licensed parts remain MIT and their notices are preserved.

Extensions, repositories and sources are **not** part of Nekomo: they are installed by the user and keep their own
licenses. Nekomo ships none of them.

'''

rows, unknown = [], []
for (g, a), v in sorted(deps.items()):
    key = f'{g}:{a}'
    lic = [norm(x) for x in licenses(g, a, v)]
    lic = ', '.join(dict.fromkeys(lic)) or OVERRIDES.get(key, '') or next((l for pre, l in PREFIX if g.startswith(pre)), '')
    if not lic:
        unknown.append(f'{key}:{v}')
        lic = 'UNKNOWN - verify'
    rows.append((key, v, lic))

with open('THIRD_PARTY_LICENSES.md', 'w', encoding='utf-8') as f:
    f.write('# Third-party licenses\n\nGenerated by `scripts/gen_licenses.py` from the resolved runtime classpath. See `LICENSE` and `README.md` for the license of Nekomo itself.\n\n')
    f.write('| Library | Version | License |\n|---|---|---|\n')
    for k, v, l in rows:
        f.write(f'| {k} | {v} | {l} |\n')
with open('app/src/main/assets/licenses.txt', 'w', encoding='utf-8') as f:
    for k, v, l in rows:
        f.write(f'{k} {v}\n    {l}\n')
print(len(rows), 'libraries;', len(unknown), 'unknown')
for u in unknown:
    print('  ', u)

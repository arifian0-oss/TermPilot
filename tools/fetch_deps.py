#!/usr/bin/env python3
"""
Mirror every dependency TermPilot needs into local-repo/ using curl
(which works through the sandbox egress proxy; Java HTTP does not).

Usage: python3 tools/fetch_deps.py
Re-run any time app/build.gradle.kts gains a new dependency.
Idempotent: cached POMs/artifacts are reused.

Strategy: BFS over POM files with Maven-ish version resolution:
- ${property} substitution incl. parent-POM properties and project.version
- version ranges ([1.0.1] etc.): prefer the declaring artifact's own version
  when it satisfies the lower bound (same-release-train convention),
  otherwise the latest version from maven-metadata.xml
Then download each artifact (jar/aar per <packaging>). Gradle resolves from
the file:// repo using the POMs.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import deque

REPO_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "local-repo")

MIRRORS = [
    "https://repo.maven.apache.org/maven2",
    "https://dl.google.com/dl/android/maven2",
    "https://maven.google.com",
    "https://plugins.gradle.org/m2",
]

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# (group, artifact, version). Mirrors the roots in app/build.gradle.kts
# (plugin markers resolve to the real plugin artifacts via their POMs).
# Compose versions pinned to match compose-bom 2024.06.00.
ROOTS = [
    ("com.android.application", "com.android.application.gradle.plugin", "8.5.2"),
    ("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin", "2.0.20"),
    ("org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.compose.gradle.plugin", "2.0.20"),
    # Buildscript classpath extras (Gradle Kotlin DSL + plugin transitive deps)
    ("org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.0.20"),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.9.22"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.22"),
    # Dynamically resolved by the Kotlin Gradle plugin (not via POM deps)
    ("org.jetbrains.kotlin", "kotlin-build-tools-impl", "2.0.20"),
    ("org.jetbrains.kotlin", "kotlin-compose-compiler-plugin-embeddable", "2.0.20"),
    # Dynamically resolved by AGP for library annotation extraction
    ("com.android.tools.lint", "lint-gradle", "31.5.2"),
    ("com.hierynomus", "sshj", "0.40.0"),
    ("com.squareup.okhttp3", "okhttp", "4.12.0"),
    ("androidx.security", "security-crypto", "1.1.0-alpha06"),
    ("androidx.datastore", "datastore-preferences", "1.1.1"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.8.1"),
    ("androidx.core", "core-ktx", "1.13.1"),
    ("androidx.lifecycle", "lifecycle-runtime-ktx", "2.8.4"),
    ("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.8.4"),
    ("androidx.activity", "activity-compose", "1.9.2"),
    ("androidx.compose.ui", "ui", "1.6.8"),
    ("androidx.compose.ui", "ui-tooling", "1.6.8"),
    ("androidx.compose.ui", "ui-tooling-preview", "1.6.8"),
    ("androidx.compose.ui", "ui-test-manifest", "1.6.8"),
    ("androidx.compose.material3", "material3", "1.2.1"),
    ("androidx.compose.material", "material-icons-extended", "1.6.8"),
    ("androidx.navigation", "navigation-compose", "2.7.7"),
    ("androidx.annotation", "annotation", "1.7.1"),
]


def gav_path(group, artifact, version, ext):
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{ext}"


def curl_fetch(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".tmp"
    r = subprocess.run(
        ["curl", "-sSL", "--retry", "2", "--max-time", "120", "-o", tmp, "-w", "%{http_code}", url],
        capture_output=True, text=True,
    )
    code = r.stdout.strip()[-3:]
    if code == "200" and os.path.exists(tmp) and os.path.getsize(tmp) > 0:
        os.replace(tmp, dest)
        return True
    if os.path.exists(tmp):
        os.remove(tmp)
    return False


def fetch_first(path):
    local = os.path.join(REPO_DIR, path)
    if os.path.exists(local) and os.path.getsize(local) > 0:
        return local
    for m in MIRRORS:
        if curl_fetch(m + "/" + path, local):
            return local
    return None


def ver_key(v):
    """Sortable key for versions like 1.6.8, 8.5.2, 1.1.0-alpha06."""
    parts = []
    for tok in re.split(r"[.\-]", v):
        parts.append((0, int(tok)) if tok.isdigit() else (1, tok))
    return parts


def latest_version(group, artifact):
    """Latest version from maven-metadata.xml across mirrors."""
    path = f"{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    best = None
    for m in MIRRORS:
        tmp = f"/tmp/metadata-{group}-{artifact}.xml"
        try:
            if not curl_fetch(m + "/" + path, tmp):
                continue
            root = ET.parse(tmp).getroot()
            for v in root.findall(".//version"):
                ver = v.text.strip()
                if best is None or ver_key(ver) > ver_key(best):
                    best = ver
        except Exception:
            continue
    return best


class PomDB:
    def __init__(self):
        self.parsed = {}   # gav -> dict(packaging, parent, raw_deps, props)
        self.props_memo = {}
        self.range_pins = {}  # (group, artifact) -> set of resolved versions

    def get(self, gav):
        if gav not in self.parsed:
            self.fetch(gav)
        return self.parsed.get(gav)

    def fetch(self, gav):
        g, a, v = gav
        local = fetch_first(gav_path(g, a, v, "pom"))
        if local is None:
            self.parsed[gav] = None
            return None
        try:
            info = self.parse(local)
        except Exception as e:
            print(f"WARN: POM parse failed for {g}:{a}:{v}: {e}", flush=True)
            self.parsed[gav] = None
            return None
        self.parsed[gav] = info
        return info

    @staticmethod
    def parse(pom_file):
        # Namespace-agnostic: some POMs (e.g. BouncyCastle) declare no xmlns.
        def local(tag):
            return tag.split("}", 1)[-1]

        def kids(elem, name):
            return [c for c in elem if local(c.tag) == name]

        def one(elem, name):
            k = kids(elem, name)
            return k[0] if k else None

        def txt(elem, name):
            c = one(elem, name)
            return c.text.strip() if c is not None and c.text else None

        root = ET.parse(pom_file).getroot()

        packaging = txt(root, "packaging") or "jar"
        parent = one(root, "parent")
        parent_gav = None
        if parent is not None:
            pg, pa, pv = txt(parent, "groupId"), txt(parent, "artifactId"), txt(parent, "version")
            if pg and pa and pv:
                parent_gav = (pg, pa, pv)

        props = {}
        pnode = one(root, "properties")
        if pnode is not None:
            for p in list(pnode):
                props[local(p.tag)] = (p.text or "").strip()

        raw_deps = []
        dnode = one(root, "dependencies")
        if dnode is not None:
            for d in kids(dnode, "dependency"):
                scope = txt(d, "scope") or "compile"
                if scope not in ("compile", "runtime"):
                    continue
                if txt(d, "optional") == "true":
                    continue
                dg, da, dv = txt(d, "groupId"), txt(d, "artifactId"), txt(d, "version")
                if dg and da:
                    raw_deps.append((dg, da, dv))

        managed = {}
        bom_imports = []
        dm = one(root, "dependencyManagement")
        if dm is not None:
            dms = one(dm, "dependencies")
            if dms is not None:
                for d in kids(dms, "dependency"):
                    dg, da, dv = txt(d, "groupId"), txt(d, "artifactId"), txt(d, "version")
                    if dg and da and dv:
                        if txt(d, "type") == "pom" and txt(d, "scope") == "import":
                            bom_imports.append((dg, da, dv))
                        else:
                            managed[(dg, da)] = dv

        return {"packaging": packaging, "parent": parent_gav,
                "raw_deps": raw_deps, "props": props, "managed": managed,
                "bom_imports": bom_imports}

    def effective_props(self, gav):
        if gav in self.props_memo:
            return self.props_memo[gav]
        props = {}
        info = self.get(gav)
        if info:
            if info["parent"]:
                props.update(self.effective_props(info["parent"]))
            props.update(info["props"])
        props["project.version"] = gav[2]
        props["project.groupId"] = gav[0]
        props["project.artifactId"] = gav[1]
        self.props_memo[gav] = props
        return props

    @staticmethod
    def subst(raw, props):
        if not raw:
            return raw
        def rep(mo):
            return props.get(mo.group(1), mo.group(0))
        prev = None
        out = raw
        for _ in range(5):  # nested properties
            out = re.sub(r"\$\{([^}]+)\}", rep, out)
            if out == prev:
                break
            prev = out
        return out

    def managed_version(self, gav, dep_group, dep_artifact):
        """Look up (group, artifact) in dependencyManagement, walking parents
        and imported BOMs."""
        seen_poms = set()
        stack = [gav]
        while stack:
            cur = stack.pop()
            if cur in seen_poms:
                continue
            seen_poms.add(cur)
            info = self.get(cur)
            if not info:
                continue
            if (dep_group, dep_artifact) in info["managed"]:
                return info["managed"][(dep_group, dep_artifact)]
            if info["parent"]:
                stack.append(info["parent"])
            for b in info.get("bom_imports", []):
                # substitute properties in the BOM coordinates
                props = self.effective_props(cur)
                bg = self.subst(b[0], props)
                ba = self.subst(b[1], props)
                bv = self.subst(b[2], props)
                if bg and ba and bv and "${" not in (bg + ba + bv):
                    stack.append((bg, ba, bv))
        return None

    def resolve_version(self, raw, gav, dep_group, dep_artifact):
        """Resolve a dependency version string to concrete, or None."""
        props = self.effective_props(gav)
        if not raw or not raw.strip():
            # Version managed via dependencyManagement.
            raw = self.managed_version(gav, dep_group, dep_artifact)
        v = self.subst(raw, props)
        if not v or "${" in v:
            return None
        v = v.strip()
        if v[0] in "[(":
            # Version range. Prefer the declaring artifact's own version when
            # it satisfies the lower bound (same-release-train convention).
            # Recorded for gradle-forces.txt (offline range resolution).
            m = re.match(r"[\[\(]\s*([^,\s\)\]]+)", v)
            lower = m.group(1) if m else None
            declaring = gav[2]
            chosen = None
            if lower and ver_key(declaring) >= ver_key(lower):
                chosen = declaring
            else:
                lv = latest_version(dep_group, dep_artifact)
                if lv and (not lower or ver_key(lv) >= ver_key(lower)):
                    chosen = lv
            if chosen:
                self.range_pins.setdefault((dep_group, dep_artifact), set()).add(chosen)
            return chosen
        return v


def main():
    os.makedirs(REPO_DIR, exist_ok=True)
    db = PomDB()
    queue = deque(ROOTS)
    seen = set()
    missing = []

    while queue:
        gav = queue.popleft()
        if gav in seen:
            continue
        seen.add(gav)
        info = db.get(gav)
        if info is None:
            missing.append(gav)
            continue
        if info["parent"] and info["parent"] not in seen:
            queue.append(info["parent"])
        for b in info.get("bom_imports", []):
            props = db.effective_props(gav)
            bg = db.subst(b[0], props)
            ba = db.subst(b[1], props)
            bv = db.subst(b[2], props)
            if bg and ba and bv and "${" not in (bg + ba + bv):
                bom_gav = (bg, ba, bv)
                if bom_gav not in seen:
                    queue.append(bom_gav)
        for dg, da, dv in info["raw_deps"]:
            rg = db.subst(dg, db.effective_props(gav))
            ra = db.subst(da, db.effective_props(gav))
            if not rg or not ra or "${" in rg or "${" in ra:
                missing.append((dg, da, dv, f"declared by {gav[0]}:{gav[1]}:{gav[2]}"))
                continue
            rv = db.resolve_version(dv, gav, rg, ra)
            if rv is None:
                missing.append((rg, ra, dv, f"declared by {gav[0]}:{gav[1]}:{gav[2]}"))
                continue
            dep = (rg, ra, rv)
            if dep not in seen:
                queue.append(dep)

    print(f"Closure: {len(seen)} GAVs, {len(missing)} unresolvable", flush=True)
    for m in missing[:20]:
        print(f"  MISSING: {m}", flush=True)

    # Download artifacts.
    n_new = 0
    n_noart = 0
    for gav in sorted(seen):
        g, a, v = gav
        info = db.parsed.get(gav) or {}
        packaging = info.get("packaging", "jar")
        if packaging == "pom":
            continue
        ext = {"aar": "aar", "bundle": "jar"}.get(packaging, "jar")
        local = os.path.join(REPO_DIR, gav_path(g, a, v, ext))
        if os.path.exists(local) and os.path.getsize(local) > 0:
            continue
        if fetch_first(gav_path(g, a, v, ext)):
            n_new += 1
        else:
            n_noart += 1
            print(f"WARN: no {ext} for {g}:{a}:{v} (packaging={packaging})", flush=True)

    print(f"Done: {n_new} artifacts fetched this run, {n_noart} missing artifacts", flush=True)

    # maven-metadata.xml for every GA: Gradle needs version listings to
    # resolve version ranges (e.g. sshj's bcutil-jdk18on:[1.80,1.81)),
    # including in offline mode.
    gas = sorted({(g, a) for g, a, _ in seen})
    n_meta = 0
    for g, a in gas:
        path = f"{g.replace('.', '/')}/{a}/maven-metadata.xml"
        local = os.path.join(REPO_DIR, path)
        if os.path.exists(local) and os.path.getsize(local) > 0:
            continue
        if fetch_first(path):
            n_meta += 1
    print(f"Metadata: {n_meta} maven-metadata.xml fetched this run", flush=True)

    # Emit Gradle force pins for every range dep: offline builds cannot list
    # versions, so ranges are pinned. When several declarers resolved
    # differently, pin the max (matches Gradle's highest-wins semantics and
    # keeps release trains aligned). Modules covered by SUBSTITUTIONS are
    # skipped (the substitution fixes the version).
    forces_path = os.path.join(os.path.dirname(REPO_DIR), "gradle-forces.txt")
    pins = 0
    with open(forces_path, "w") as f:
        f.write("# Generated by tools/fetch_deps.py — pins for range deps.\n")
        f.write("# Applied in app/build.gradle.kts via resolutionStrategy.\n")
        for (g, a), vs in sorted(db.range_pins.items()):
            if (g, a) in SUBSTITUTIONS:
                continue
            v = sorted(vs, key=ver_key)[-1]
            # sanity: the pinned version must actually be mirrored
            if os.path.exists(os.path.join(REPO_DIR, gav_path(g, a, v, "pom"))):
                f.write(f"{g}:{a}:{v}\n")
                pins += 1
            else:
                print(f"WARN: pin {g}:{a}:{v} not mirrored, skipped", flush=True)
    print(f"Forces: {pins} pins -> gradle-forces.txt", flush=True)
    print(f"Repo: {REPO_DIR} ({sum(len(f) for _, _, fs in os.walk(REPO_DIR) for f in fs)} files)", flush=True)


if __name__ == "__main__":
    sys.exit(main())

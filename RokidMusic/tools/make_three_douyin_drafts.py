#!/usr/bin/env python3
"""Write reviewable first-pass drafts for the three manually supplied videos."""
from __future__ import annotations
import json
from pathlib import Path

PPQ = 960
BAR = 3840

SOURCES = {
    "花海": ("花海_douyin.mp4", "花海_douyin", "周杰伦", 72, 1),
    "天下": ("天下_douyin.mp4", "天下_douyin", "张杰", 130, 2),
    "老男孩": ("老男孩_douyin.mp4", "老男孩_douyin", "筷子兄弟", 72, 3),
}


def ev(mid, i, string, fret, base=8, beam=None, status="normal", display=None):
    eid = f"{mid}e{i}"
    return {"id": eid, "type": "note", "tick": (i - 1) * (3840 // base),
            "duration": {"base": base, "dots": 0}, "voice": 1,
            "beamGroup": beam,
            "notes": [{"id": f"{eid}n1", "string": string, "fret": fret,
                       "display": display or str(fret), "status": status, "effects": []}],
            "articulations": []}


def rest(mid, i, base=4):
    return {"id": f"{mid}e{i}", "type": "rest", "tick": (i - 1) * (3840 // base),
            "duration": {"base": base, "dots": 0}, "voice": 1}


def bar(number, values, frame, techniques=None, default_string=2, base=8):
    mid = f"m{number}"
    events = []
    beam = f"m{number}b1" if base >= 8 else None
    for i, value in enumerate(values, 1):
        if value == "rest":
            events.append(rest(mid, i, 4))
            continue
        if isinstance(value, tuple):
            string, fret = value
        else:
            string, fret = default_string, value
        if isinstance(fret, str) and fret.startswith("("):
            events.append(ev(mid, i, string, int(fret.strip("()")), base, beam, "tied", fret))
        elif fret == "X":
            events.append(ev(mid, i, string, 0, base, beam, "dead", "x"))
        else:
            events.append(ev(mid, i, string, int(fret), base, beam))
    return {"id": mid, "number": number, "trackId": "gtr1", "startTick": (number - 1) * BAR,
            "durationTicks": BAR, "attributes": {},
            "barline": {"left": "single", "right": "final" if number == 16 else "single",
                         "repeatStart": False, "repeatEnd": False, "repeatCount": None, "ending": None},
            "events": events, "spanners": techniques or [], "directions": [],
            "source": {"frame": frame, "confidence": 0.55, "rawText": "first-pass visual transcription"}}


def link(sid, kind, a, b=None, **extra):
    out = {"id": sid, "type": kind, "from": a}
    if b: out["to"] = b
    out.update(extra)
    return out


def score(title, artist, tempo, source_file, stem, measures):
    return {"schema": "rokid.music.tab-score", "schemaVersion": 1,
            "metadata": {"title": title, "artist": artist},
            "defaults": {"ppq": PPQ, "tempo": {"bpm": tempo, "beatUnit": 4, "text": None},
                         "timeSignature": {"beats": 4, "beatType": 4, "symbol": "normal", "visible": True},
                         "keySignature": {"fifths": 0, "mode": "major", "visible": False},
                         "tuning": {"name": "Standard tuning", "capo": 0,
                                    "strings": [{"number": n, "pitch": p} for n, p in enumerate(["E4", "B3", "G3", "D3", "A2", "E2"], 1)]},
                         "notation": {"staff": "tab", "showRhythm": True, "showStringLabels": True, "colorProfile": "rokid-green"}},
            "tracks": [{"id": "gtr1", "name": "Electric Guitar", "instrument": "electric-guitar", "midiProgram": 30, "stringCount": 6, "tuningRef": "defaults.tuning", "visible": True, "playback": {"muted": False, "solo": False}}],
            "systems": [{"id": f"sys-{i}", "measureIds": [f"m{n}" for n in range(i, min(i + 4, len(measures) + 1))]} for i in range(1, len(measures) + 1, 4)],
            "measures": measures,
            "source": {"video": f"data/raw/{source_file}", "motionMode": "see motion_analysis.json", "transcriptionStatus": "draft-needs-review"}}


def main():
    root = Path(__file__).resolve().parents[1]
    # These are intentionally conservative first passes from the visible TAB;
    # the editor can correct strings, timing, beams, and technique endpoints.
    flower = [
        bar(1, [14, 16, 16, 18, 17, 17, "(17)"], 1, [link("m1s1", "hammer-on", "m1e1n1", "m1e2n1", label="H"), link("m1s2", "slide", "m1e4n1", "m1e5n1", label="sl.")]),
        bar(2, [18, 16, 14, 16, 16, 18, "(18)"], 1, [link("m2s1", "slide", "m2e1n1", "m2e2n1", label="sl.")]),
        bar(3, ["(18)", 14, 16, 16, 18], 6, [link("m3s1", "tie", "m3e1n1", "m3e2n1", placement="below")]),
        bar(4, [14, 16, 18, 17, 17, 19, "(19)"], 12, [link("m4s1", "vibrato", "m4e3n1", width="normal")]),
        bar(5, [14, 16, 17, 16, 17, 19], 20, [link("m5s1", "slide", "m5e1n1", "m5e3n1", label="sl.")]),
        bar(6, [17, 19, 17, 19, "(19)"], 28, [link("m6s1", "bend", "m6e1n1", label="1/2", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 1}, {"at": 1, "alter": 0}])]),
    ]
    world = [
        bar(1, ["rest"], 1, default_string=1, base=1), bar(2, ["rest"], 1, default_string=1, base=1),
        bar(3, ["rest", "rest", "X"], 1, default_string=1, base=4),
        bar(4, [(2, 6), (3, 4), (2, 6), (3, 4), (2, 6), (3, 4), (2, 6), (3, 4)], 4, [link("m4s1", "palm-mute", "m4e1", "m4e8", label="P.M.", line="dashed")], base=8),
        bar(5, [(2, 6), (3, 4), (2, 6), (3, 4), (2, 6), (3, 4), (2, 6), (3, 4)], 5, [link("m5s1", "palm-mute", "m5e1", "m5e8", label="P.M.", line="dashed")], base=8),
        bar(6, [6, 6, 7, 1, 2, 3, 3, 0], 10, [link("m6s1", "slide", "m6e2n1", "m6e3n1", label="sl.")], base=8),
        bar(7, [3, 3, 0, 2, 2, 7, 6, 5], 14, [link("m7s1", "slide", "m7e1n1", "m7e2n1", label="sl.")], base=8),
        bar(8, [6, 6, 6, 6, 6, 6, 6, 6], 18, [link("m8s1", "palm-mute", "m8e1", "m8e8", label="P.M.", line="dashed")], base=8),
        bar(9, [2, 7, 6, 5, 6, 6, 6, 6], 22, [link("m9s1", "vibrato", "m9e2n1", width="normal")], base=8),
        bar(10, [3, 3, 0, 2, 2, 7, 6, 5], 26, [], base=8),
        bar(11, [6, 6, 6, 6, 6, 6, 6, 6], 30, [link("m11s1", "palm-mute", "m11e1", "m11e8", label="P.M.", line="dashed")], base=8),
        bar(12, [2, 7, 6, 5, 6, 6, 6, 6], 34, [link("m12s1", "slide", "m12e2n1", "m12e3n1", label="sl.")], base=8),
    ]
    old = [
        bar(1, [7, (2, "(8)"), 6, 5, 5, 6, 8, (1, "(10)")], 1, [link("m1s1", "bend", "m1e2n1", label="1/2", curve=[{"at": 0, "alter": 0}, {"at": .5, "alter": 1}, {"at": 1, "alter": 0}]), link("m1s2", "hammer-on", "m1e4n1", "m1e5n1", label="H")]),
        bar(2, [5, 5, 6, 6, 8, 10, "(8)", 5], 1, [link("m2s1", "slide", "m2e3n1", "m2e4n1", label="sl.")]),
        bar(3, [5, "(5)", 7, 8, 10, "(8)", 6, 5], 8, [link("m3s1", "pull-off", "m3e3n1", "m3e4n1", label="P")]),
        bar(4, [15, 15, 13, 13, 12, 12, 13, 13], 14, [link("m4s1", "vibrato", "m4e1n1", width="wide"), link("m4s2", "slide", "m4e5n1", "m4e6n1", label="sl.")]),
        bar(5, [19, 19, 20, 19, 17, 16, "(16)", 17], 20, [link("m5s1", "bend", "m5e1n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}])]),
        bar(6, [19, 19, 17, 17, 16, 16, 17, 17], 26, [link("m6s1", "hammer-on", "m6e3n1", "m6e4n1", label="H")]),
    ]
    flower.extend([
        bar(7, [17, 16, 18, 16, 14, 16, 14, "(14)"], 35, [link("m7s1", "hammer-on", "m7e1n1", "m7e2n1", label="H"), link("m7s2", "slide", "m7e3n1", "m7e4n1", label="sl.")]),
        bar(8, [14, 16, 16, 18, 17, 15, 15, 14], 40, [link("m8s1", "pull-off", "m8e2n1", "m8e3n1", label="P")]),
        bar(9, [15, 17, 12, 14, 12, 14, 13, 14], 48, [link("m9s1", "hammer-on", "m9e1n1", "m9e2n1", label="H")]),
        bar(10, [17, 17, 16, 17, 16, 19, 17, 16], 56, [link("m10s1", "slide", "m10e5n1", "m10e6n1", label="sl.")]),
        bar(11, [17, 16, 17, 16, 17, 19, 19, "(19)"], 64, [link("m11s1", "hammer-on", "m11e1n1", "m11e2n1", label="H"), link("m11s2", "pull-off", "m11e2n1", "m11e3n1", label="P")]),
        bar(12, [19, 17, 19, 19, "(19)", 17], 72, [link("m12s1", "hammer-on", "m12e3n1", "m12e4n1", label="H"), link("m12s2", "pull-off", "m12e4n1", "m12e5n1", label="P")]),
        bar(13, [17, 17, 14, 14, 16, 16, 18], 80, [link("m13s1", "slide", "m13e1n1", "m13e2n1", label="sl.")]),
        bar(14, [14, 16, 14, 16, 16, 18, 17, 17], 88, [link("m14s1", "vibrato", "m14e4n1", width="normal")]),
        bar(15, [16, 14, 14, 16, 14, 16, 17, "(17)"], 96, [link("m15s1", "slide", "m15e2n1", "m15e3n1", label="sl.")]),
        bar(16, [16, 14, 14, 16, 16, 18, 17, 16], 104, [], base=8),
        bar(17, [17, 17, 19, 17, 16, 17, 16, 17], 112, [link("m17s1", "hammer-on", "m17e1n1", "m17e2n1", label="H")]),
        bar(18, [19, 17, 19, 19, 17, 16, 17, 16], 120, [link("m18s1", "bend", "m18e1n1", label="1/2", curve=[{"at": 0, "alter": 0}, {"at": .6, "alter": 1}, {"at": 1, "alter": 0}])]),
        bar(19, [16, 14, 14, 16, 16, 18, 17, 16], 128, [link("m19s1", "slide", "m19e1n1", "m19e2n1", label="sl.")]),
        bar(20, [13, 14, 13, 14, 13, 14, 16], 136, [link("m20s1", "hammer-on", "m20e3n1", "m20e4n1", label="H"), link("m20s2", "pull-off", "m20e4n1", "m20e5n1", label="P")]),
    ])
    world.extend([
        bar(13, [6, 6, 6, 6, 6, 6, 6, 6], 38, [link("m13s1", "palm-mute", "m13e1", "m13e8", label="P.M.", line="dashed")], base=8),
        bar(14, [6, 7, 1, 2, 3, 3, 0, 2], 42, [link("m14s1", "slide", "m14e2n1", "m14e3n1", label="sl.")], base=8),
        bar(15, [2, 7, 6, 5, 6, 6, 6, 6], 46, [link("m15s1", "slide", "m15e1n1", "m15e2n1", label="sl.")], base=8),
        bar(16, [6, 6, 6, 6, 6, 6, 6, 6], 50, [link("m16s1", "palm-mute", "m16e1", "m16e8", label="P.M.", line="dashed")], base=8),
        bar(17, [5, 7, 7, 6, 6, 5, 5, 4], 54, [link("m17s1", "slide", "m17e2n1", "m17e3n1", label="sl.")], base=8),
        bar(18, [4, 5, 7, 7, 9, 7, "(7)", 5], 58, [link("m18s1", "vibrato", "m18e1n1", width="normal")], base=8),
        bar(19, [5, 6, 1, 1, 2, 2, 3, 6], 62, [link("m19s1", "hammer-on", "m19e5n1", "m19e6n1", label="H")], base=8),
        bar(20, [1, 2, 2, 3, 6, 6, 6, 6], 66, [link("m20s1", "vibrato", "m20e1n1", width="normal")], base=8),
        bar(21, [6, 6, 6, 6, 6, 6, 6, 6], 70, [link("m21s1", "palm-mute", "m21e1", "m21e8", label="P.M.", line="dashed")], base=8),
    ])
    old.extend([
        bar(7, [15, 15, 13, 13, 12, 13, 12, "(12)"], 40, [link("m7s1", "hammer-on", "m7e1n1", "m7e2n1", label="H"), link("m7s2", "pull-off", "m7e2n1", "m7e3n1", label="P")]),
        bar(8, [7, 19, "(19)", 20, 19, 17, 16, "(16)"], 60, [link("m8s1", "bend", "m8e2n1", label="1/2", curve=[{"at": 0, "alter": 0}, {"at": .6, "alter": 1}, {"at": 1, "alter": 0}])]),
        bar(9, [17, 19, 19, 17, 16, 17, 20], 70, [link("m9s1", "bend", "m9e3n1", label="full", curve=[{"at": 0, "alter": 0}, {"at": .7, "alter": 2}, {"at": 1, "alter": 0}]), link("m9s2", "slide", "m9e5n1", "m9e6n1", label="sl.")]),
    ])
    for title, data, measures in [("花海", SOURCES["花海"], flower), ("天下", SOURCES["天下"], world), ("老男孩", SOURCES["老男孩"], old)]:
        source_file, stem, artist, tempo, _ = data
        ascii_names = {
            "花海_douyin": "huahai_douyin.tab.json",
            "天下_douyin": "tianxia_douyin.tab.json",
            "老男孩_douyin": "laonanhai_douyin.tab.json",
        }
        out = root / "data" / "music" / ascii_names[stem]
        out.write_text(json.dumps(score(title, artist, tempo, source_file, stem, measures), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(out)


if __name__ == "__main__":
    main()

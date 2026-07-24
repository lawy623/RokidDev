#!/usr/bin/env python3
"""Generate a measure-map aligned draft JSON for the Douyin transcription.

This starts from step 4 of the video transcription workflow: every measure is
created from the verified measure map before notes and techniques are filled in.
"""

from __future__ import annotations

import json
from pathlib import Path


PPQ = 960
MEASURE_TICKS = PPQ * 4
TRACK_ID = "gtr1"
SOURCE_ID = "src-douyin-7379895619067858213"


TRANSCRIPTIONS = {
    1: {
        "events": [
            {"type": "rest", "tick": 0, "base": 4},
            {"type": "rest", "tick": 960, "base": 4},
            {"type": "rest", "tick": 1920, "base": 8},
            {"type": "note", "tick": 2400, "base": 8, "string": 3, "fret": 12},
            {"type": "note", "tick": 2880, "base": 8, "string": 2, "fret": 13, "beam": "m1b1"},
            {"type": "note", "tick": 3360, "base": 8, "string": 2, "fret": 15, "beam": "m1b1", "articulations": [{"type": "staccato"}]},
        ],
        "spanners": [],
    },
    2: {
        "events": [
            {"type": "note", "tick": 0, "base": 4, "string": 2, "fret": 15},
            {"type": "note", "tick": 960, "base": 8, "string": 2, "fret": 15, "beam": "m2b1"},
            {"type": "note", "tick": 1440, "base": 16, "string": 2, "fret": 13, "beam": "m2b1"},
            {"type": "note", "tick": 1680, "base": 16, "string": 2, "fret": 15, "beam": "m2b1"},
            {"type": "note", "tick": 1920, "base": 4, "dots": 1, "string": 2, "fret": 15},
            {"type": "note", "tick": 3360, "base": 8, "string": 2, "fret": 15},
        ],
        "spanners": [
            {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
            {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
            {"type": "vibrato", "from": "n5", "toEvent": "e6", "width": "normal"},
            {"type": "bend", "from": "n6", "label": "full", "curve": "full"},
            {"type": "tie", "from": "n6", "to": "m3n1_1", "placement": "below"},
        ],
    },
    3: {
        "events": [
            {"type": "note", "tick": 0, "base": 8, "string": 2, "fret": 15, "display": "(15)", "status": "ghost", "beam": "m3b1"},
            {"type": "note", "tick": 480, "base": 8, "string": 2, "fret": 13, "articulations": [{"type": "staccato"}], "beam": "m3b1"},
            {"type": "note", "tick": 960, "base": 16, "string": 2, "fret": 12, "beam": "m3b2"},
            {"type": "note", "tick": 1200, "base": 16, "string": 2, "fret": 13, "beam": "m3b2"},
            {"type": "note", "tick": 1440, "base": 8, "string": 2, "fret": 12, "beam": "m3b2"},
            {"type": "note", "tick": 1920, "base": 4, "dots": 1, "string": 3, "fret": 14},
            {"type": "note", "tick": 3360, "base": 16, "string": 2, "fret": 13, "beam": "m3b3"},
            {"type": "note", "tick": 3600, "base": 16, "string": 2, "fret": 15, "beam": "m3b3"},
        ],
        "spanners": [
            {"type": "hammer-on", "from": "n3", "to": "n4", "label": "H"},
            {"type": "pull-off", "from": "n4", "to": "n5", "label": "P"},
            {"type": "vibrato", "from": "n6", "to": "n7", "width": "normal"},
        ],
    },
    4: {
        "events": [
            {"type": "note", "tick": 0, "base": 4, "string": 2, "fret": 15},
            {"type": "note", "tick": 960, "base": 8, "string": 2, "fret": 15, "beam": "m4b1"},
            {"type": "note", "tick": 1440, "base": 16, "string": 2, "fret": 13, "beam": "m4b1"},
            {"type": "note", "tick": 1680, "base": 16, "string": 2, "fret": 15, "beam": "m4b1"},
            {"type": "note", "tick": 1920, "base": 4, "string": 2, "fret": 15},
            {"type": "note", "tick": 2880, "base": 16, "string": 2, "fret": 15, "beam": "m4b2"},
            {"type": "note", "tick": 3120, "base": 16, "string": 2, "fret": 13, "beam": "m4b2"},
            {"type": "note", "tick": 3360, "base": 16, "string": 2, "fret": 12, "beam": "m4b2"},
            {"type": "note", "tick": 3600, "base": 16, "string": 2, "fret": 13, "beam": "m4b2"},
        ],
        "spanners": [
            {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
            {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
            {"type": "vibrato", "from": "n5", "to": "n6", "width": "normal"},
            {"type": "hammer-on", "from": "n8", "to": "n9", "label": "H"},
        ],
    },
    5: {
        "events": [
            {"type": "note", "tick": 0, "base": 8, "string": 2, "fret": 13, "beam": "m5b1"},
            {"type": "note", "tick": 480, "base": 8, "string": 3, "fret": 12, "beam": "m5b1"},
            {"type": "note", "tick": 960, "base": 16, "string": 2, "fret": 13, "beam": "m5b2"},
            {"type": "note", "tick": 1200, "base": 16, "string": 2, "fret": 15, "beam": "m5b2"},
            {"type": "note", "tick": 1440, "base": 16, "string": 2, "fret": 12, "beam": "m5b2"},
            {"type": "note", "tick": 1680, "base": 16, "string": 2, "fret": 13, "beam": "m5b2"},
            {"type": "note", "tick": 1920, "base": 8, "string": 2, "fret": 12, "beam": "m5b3"},
            {"type": "note", "tick": 2400, "base": 8, "string": 2, "fret": 13, "beam": "m5b3"},
            {"type": "note", "tick": 2880, "base": 8, "string": 2, "fret": 13, "beam": "m5b4"},
            {"type": "note", "tick": 3360, "base": 8, "string": 2, "fret": 15, "beam": "m5b4"},
        ],
        "spanners": [],
    },
    6: {
        "events": [
            {"type": "note", "tick": 0, "base": 4, "string": 2, "fret": 15},
            {"type": "note", "tick": 960, "base": 8, "string": 2, "fret": 15, "beam": "m6b1"},
            {"type": "note", "tick": 1440, "base": 16, "string": 2, "fret": 13, "beam": "m6b1"},
            {"type": "note", "tick": 1680, "base": 16, "string": 2, "fret": 15, "beam": "m6b1"},
            {"type": "note", "tick": 1920, "base": 4, "dots": 1, "string": 2, "fret": 15},
            {"type": "note", "tick": 3360, "base": 8, "string": 2, "fret": 15},
        ],
        "spanners": [
            {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
            {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
            {"type": "vibrato", "from": "n5", "toEvent": "e6", "width": "normal"},
            {"type": "bend", "from": "n6", "label": "full", "curve": "full"},
        ],
    },
    7: {
        "events": [
            {"type": "note", "tick": 0, "base": 8, "string": 2, "fret": 15, "display": "(15)", "status": "ghost", "beam": "m7b1"},
            {"type": "note", "tick": 480, "base": 8, "string": 2, "fret": 13, "beam": "m7b1"},
            {"type": "note", "tick": 960, "base": 16, "string": 2, "fret": 12, "beam": "m7b2"},
            {"type": "note", "tick": 1200, "base": 16, "string": 2, "fret": 13, "beam": "m7b2"},
            {"type": "note", "tick": 1440, "base": 8, "string": 2, "fret": 12, "beam": "m7b2"},
            {"type": "note", "tick": 1920, "base": 4, "string": 3, "fret": 14},
            {"type": "note", "tick": 2880, "base": 8, "string": 2, "fret": 13, "beam": "m7b3"},
            {"type": "note", "tick": 3360, "base": 16, "string": 2, "fret": 13, "beam": "m7b3"},
            {"type": "note", "tick": 3600, "base": 16, "string": 2, "fret": 15, "beam": "m7b3"},
        ],
        "spanners": [
            {"type": "hammer-on", "from": "n3", "to": "n4", "label": "H"},
            {"type": "pull-off", "from": "n4", "to": "n5", "label": "P"},
            {"type": "vibrato", "from": "n6", "toEvent": "e6", "width": "normal"},
        ],
    },
    8: {
        "events": [
            {"type": "note", "tick": 0, "base": 4, "string": 2, "fret": 15},
            {"type": "note", "tick": 960, "base": 8, "string": 2, "fret": 15, "beam": "m8b1"},
            {"type": "note", "tick": 1440, "base": 16, "string": 2, "fret": 13, "beam": "m8b1"},
            {"type": "note", "tick": 1680, "base": 16, "string": 2, "fret": 15, "beam": "m8b1"},
            {"type": "note", "tick": 1920, "base": 8, "string": 2, "fret": 15, "beam": "m8b2"},
            {"type": "note", "tick": 2400, "base": 8, "string": 2, "fret": 15, "beam": "m8b2"},
            {"type": "note", "tick": 2880, "base": 16, "string": 2, "fret": 13, "beam": "m8b3"},
            {"type": "note", "tick": 3120, "base": 16, "string": 2, "fret": 12, "beam": "m8b3"},
            {"type": "note", "tick": 3360, "base": 16, "string": 2, "fret": 13, "beam": "m8b3"},
        ],
        "spanners": [
            {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
            {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
            {"type": "vibrato", "from": "n5", "toEvent": "e6", "width": "normal"},
            {"type": "hammer-on", "from": "n7", "to": "n9", "label": "H"},
        ],
    },
    9: {
        "events": [
            {"type": "note", "tick": 0, "base": 4, "string": 2, "fret": 13},
            {"type": "note", "tick": 960, "base": 8, "string": 2, "fret": 12, "beam": "m9b1"},
            {"type": "note", "tick": 1440, "base": 8, "string": 3, "fret": 10, "beam": "m9b1"},
            {"type": "note", "tick": 1920, "base": 4, "string": 3, "fret": 9},
        ],
        "spanners": [
            {"type": "vibrato", "from": "n1", "toEvent": "e1", "width": "normal"},
            {"type": "slide", "from": "n2", "to": "n3", "label": "sl.", "direction": "down"},
        ],
    },
    10: {
        "events": [
            {"type": "rest", "tick": 0, "base": 4},
            {"type": "rest", "tick": 960, "base": 4},
            {"type": "note", "tick": 1920, "base": 8, "string": 2, "fret": 13, "beam": "m10b1"},
            {"type": "note", "tick": 2400, "base": 8, "string": 3, "fret": 12, "beam": "m10b1"},
            {"type": "note", "tick": 2880, "base": 8, "string": 2, "fret": 13, "beam": "m10b2"},
        ],
        "spanners": [],
    },
}


def note(tick: int, string: int, fret: int, base: int, beam: str | None = None, **extra) -> dict:
    out = {"type": "note", "tick": tick, "base": base, "string": string, "fret": fret}
    if beam:
        out["beam"] = beam
    out.update(extra)
    return out


def chord(tick: int, notes: list[tuple[int, int]], base: int, beam: str | None = None) -> dict:
    out = {
        "type": "note",
        "tick": tick,
        "base": base,
        "notes": [{"string": string, "fret": fret} for string, fret in notes],
    }
    if beam:
        out["beam"] = beam
    return out


def rest(tick: int, base: int, **extra) -> dict:
    out = {"type": "rest", "tick": tick, "base": base}
    out.update(extra)
    return out


def seq_events(measure_number: int, values: list[int | tuple[int, int]], default_string: int = 3) -> list[dict]:
    count = len(values)
    if count <= 4:
        step, base = 960, 4
    elif count <= 8:
        step, base = 480, 8
    else:
        step, base = 240, 16
    beam = f"m{measure_number}b1" if base >= 8 else None
    events = []
    for index, value in enumerate(values):
        if isinstance(value, tuple):
            string, fret = value
        else:
            string, fret = default_string, value
        events.append(note(index * step, string, fret, base, beam))
    return events


TRANSCRIPTIONS.update(
    {
        11: {
            "events": seq_events(11, [(2, 12), (2, 13), (2, 13), (2, 13), (2, 15), (2, 13), (2, 13), (2, 12), (2, 13), (2, 12), (2, 13), (2, 12)], 2),
            "spanners": [{"type": "let-ring", "fromEvent": "e1", "toEvent": "e10", "label": "let ring"}],
        },
        12: {
            "events": seq_events(12, [(2, 13), (2, 12), (2, 13), (2, 12), (2, 14), (2, 12), (2, 14)], 2),
            "spanners": [
                {"type": "hammer-on", "from": "n4", "to": "n5", "label": "H"},
                {"type": "pull-off", "from": "n5", "to": "n6", "label": "P"},
                {"type": "hammer-on", "from": "n6", "to": "n7", "label": "H"},
            ],
        },
        13: {
            "events": seq_events(13, [(2, 14), (2, 12), (3, 10), (3, 9), (3, 12), (3, 10), (3, 9), (3, 10)], 3),
            "spanners": [
                {"type": "vibrato", "from": "n1", "toEvent": "e1", "width": "normal"},
                {"type": "slide", "from": "n2", "to": "n3", "label": "sl.", "direction": "down"},
            ],
        },
        14: {
            "events": seq_events(14, [10, 9, 10, 12, 10, 9, 10, 9, 12, 10, 9, 10, 9], 3),
            "spanners": [
                {"type": "palm-mute", "fromEvent": "e1", "toEvent": "e13", "label": "P.M."},
                {"type": "hammer-on", "from": "n5", "to": "n6", "label": "H"},
                {"type": "pull-off", "from": "n6", "to": "n7", "label": "P"},
            ],
        },
        15: {
            "events": seq_events(15, [10, 10, 7, 5, 5, 4, 7, 9, 9, 7], 3),
            "spanners": [{"type": "palm-mute", "fromEvent": "e1", "toEvent": "e10", "label": "P.M."}],
        },
        16: {
            "events": [note(0, 3, 8, 4), note(960, 3, 8, 8, "m16b1", display="(8)", status="ghost"), note(1440, 3, 8, 8, "m16b1"), note(1920, 3, 6, 4)],
            "spanners": [
                {"type": "vibrato", "from": "n1", "toEvent": "e1", "width": "normal"},
                {"type": "bend", "from": "n2", "label": "full", "curve": "full"},
                {"type": "vibrato", "from": "n3", "toEvent": "e3", "width": "normal"},
            ],
        },
        17: {
            "events": seq_events(17, [5, 5, 7, 5, 4, 5, 7, 7], 3),
            "spanners": [
                {"type": "bend", "from": "n1", "label": "1/2", "curve": "half"},
                {"type": "bend", "from": "n2", "label": "1/2", "curve": "half"},
                {"type": "slide", "from": "n5", "to": "n6", "label": "sl.", "direction": "down"},
                {"type": "slide", "from": "n6", "to": "n7", "label": "sl.", "direction": "up"},
            ],
        },
        18: {
            "events": seq_events(18, [7, 9, 10, 9, 9, 10, 12, 10, 12, 13, 10, 12, 13], 3),
            "spanners": [
                {"type": "palm-mute", "fromEvent": "e1", "toEvent": "e13", "label": "P.M."},
                {"type": "hammer-on", "from": "n4", "to": "n5", "label": "H"},
                {"type": "pull-off", "from": "n5", "to": "n6", "label": "P"},
                {"type": "hammer-on", "from": "n12", "to": "n13", "label": "H"},
            ],
        },
        19: {
            "events": seq_events(19, [(2, 12), (2, 13), (2, 13), (2, 15), (2, 13), (2, 17), (2, 13), (2, 15), (2, 13)], 2),
            "spanners": [
                {"type": "slide", "from": "n1", "to": "n2", "label": "sl.", "direction": "up"},
                {"type": "vibrato", "from": "n1", "toEvent": "e2", "width": "normal"},
            ],
        },
        20: {
            "events": [
                chord(0, [(1, 13), (2, 13)], 2),
                chord(1920, [(1, 15), (2, 15)], 2),
            ],
            "spanners": [],
        },
        21: {
            "events": [
                chord(0, [(1, 13), (2, 13)], 2),
                chord(1920, [(1, 15), (2, 15)], 2),
            ],
            "spanners": [],
        },
        22: {
            "events": [rest(0, 4), rest(960, 4), rest(1920, 8), note(2400, 3, 5, 8, "m22b1"), note(2880, 3, 5, 8, "m22b1"), note(3360, 3, 5, 8, "m22b1"), note(3600, 3, 7, 16, "m22b1")],
            "spanners": [{"type": "slide", "from": "n1", "to": "n2", "label": "sl.", "direction": "up"}],
        },
        23: {
            "events": seq_events(23, [7, 7, 7, 5, 7, 7, 7], 3),
            "spanners": [
                {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
                {"type": "bend", "from": "n3", "label": "full", "curve": "release-from-full", "preBend": True},
                {"type": "vibrato", "from": "n7", "toEvent": "e7", "width": "normal"},
            ],
        },
        24: {
            "events": seq_events(24, [7, 5, 4, 5, 4, 5, 7, 2, 7], 3),
            "spanners": [
                {"type": "hammer-on", "from": "n3", "to": "n4", "label": "H"},
                {"type": "slide", "from": "n8", "to": "n9", "label": "sl.", "direction": "up"},
            ],
        },
        25: {
            "events": seq_events(25, [7, 5, 4, 5, 4, 5, 7, 2, 7], 3),
            "spanners": [
                {"type": "tie", "from": "n1", "to": "n2"},
                {"type": "hammer-on", "from": "n3", "to": "n4", "label": "H"},
                {"type": "slide", "from": "n8", "to": "n9", "label": "sl.", "direction": "up"},
            ],
        },
        26: {
            "events": [note(0, 3, 9, 4), note(960, 3, 5, 8, "m26b1"), note(1440, 3, 12, 8, "m26b1"), note(1920, 3, 12, 4), note(2880, 2, 15, 4)],
            "spanners": [
                {"type": "vibrato", "from": "n1", "toEvent": "e1", "width": "normal"},
                {"type": "slide", "from": "n2", "to": "n3", "label": "sl.", "direction": "up"},
                {"type": "tie", "from": "n3", "to": "n4"},
                {"type": "vibrato", "from": "n5", "toEvent": "e5", "width": "normal"},
            ],
        },
        27: {
            "events": seq_events(27, [(2, 15), (2, 10), (2, 8), (2, 7), (3, 10), (2, 8), (2, 12), (2, 12), (2, 13), (2, 15)], 2),
            "spanners": [
                {"type": "slide", "from": "n6", "to": "n7", "label": "sl.", "direction": "up"},
                {"type": "tie", "from": "n7", "to": "n8"},
                {"type": "vibrato", "from": "n8", "toEvent": "e8", "width": "normal"},
            ],
        },
        28: {
            "events": seq_events(28, [(2, 15), (2, 15), (2, 15), (2, 13), (2, 15), (2, 15), (2, 15)], 2),
            "spanners": [
                {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
                {"type": "bend", "from": "n2", "label": "full", "curve": "full"},
                {"type": "vibrato", "from": "n6", "toEvent": "e7", "width": "normal"},
            ],
        },
        29: {
            "events": seq_events(29, [(2, 15), (2, 15), (2, 15), (2, 15), (2, 13), (2, 12), (3, 14), (2, 13), (2, 15)], 2),
            "spanners": [
                {"type": "bend", "from": "n1", "label": "3/4", "curve": "half"},
                {"type": "bend", "from": "n2", "label": "1/2", "curve": "half"},
                {"type": "bend", "from": "n3", "label": "1/4", "curve": "quarter"},
                {"type": "slide", "from": "n5", "to": "n6", "label": "sl.", "direction": "down"},
            ],
        },
        30: {
            "events": seq_events(30, [(2, 15), (2, 15), (2, 15), (2, 15)], 2),
            "spanners": [
                {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
                {"type": "bend", "from": "n2", "label": "full", "curve": "release-from-full", "preBend": True},
                {"type": "bend", "from": "n4", "label": "full", "curve": "full"},
            ],
        },
        31: {
            "events": seq_events(31, [(2, 13), (3, 14), (3, 5), (3, 7), (3, 5), (3, 4), (3, 5), (3, 7), (3, 5), (3, 7)], 3),
            "spanners": [
                {"type": "vibrato", "from": "n1", "toEvent": "e1", "width": "normal"},
                {"type": "palm-mute", "fromEvent": "e3", "toEvent": "e10", "label": "P.M."},
                {"type": "slide", "from": "n2", "to": "n3", "label": "sl.", "direction": "down"},
            ],
        },
        32: {
            "events": seq_events(32, [7, 7, 7, 5, 7, 7, 7], 3),
            "spanners": [
                {"type": "bend", "from": "n1", "label": "full", "curve": "full"},
                {"type": "bend", "from": "n3", "label": "full", "curve": "release-from-full", "preBend": True},
                {"type": "vibrato", "from": "n7", "toEvent": "e7", "width": "normal"},
            ],
        },
        33: {
            "events": seq_events(33, [4, 5, 4, 5, 7, 5, 7, 12], 3),
            "spanners": [
                {"type": "hammer-on", "from": "n1", "to": "n2", "label": "H"},
                {"type": "slide", "from": "n5", "to": "n6", "label": "sl.", "direction": "up"},
                {"type": "slide", "from": "n7", "to": "n8", "label": "sl.", "direction": "up"},
                {"type": "vibrato", "from": "n8", "toEvent": "e8", "width": "normal"},
            ],
        },
        34: {
            "events": seq_events(34, [9, 10, 12, 9, 10, 9, 12, 9, 10, 12, 10, 12, 13, 10, 12, 13, 13, 12, 10], 3),
            "spanners": [{"type": "pull-off", "from": "n3", "to": "n6", "label": "P"}],
        },
        35: {
            "events": seq_events(35, [7, 5, 5, 7, 5, 5, 7], 3),
            "spanners": [
                {"type": "slide", "from": "n1", "to": "n2", "label": "sl.", "direction": "down"},
                {"type": "palm-mute", "fromEvent": "e1", "toEvent": "e7", "label": "P.M."},
            ],
        },
        36: {
            "events": [note(0, 3, 5, 4), note(960, 3, 8, 4, None, display="(8)", status="ghost"), note(1920, 3, 5, 4), note(2880, 3, 5, 4, None, display="(5)", status="ghost")],
            "spanners": [
                {"type": "bend", "from": "n1", "label": "1/2", "curve": "half"},
                {"type": "bend", "from": "n2", "label": "1/2", "curve": "half"},
                {"type": "vibrato", "from": "n4", "toEvent": "e4", "width": "normal"},
            ],
        },
    }
)


def source_map(measure: dict) -> dict:
    return {
        "sourceId": SOURCE_ID,
        "page": None,
        "frame": None,
        "bbox": [0, 0, 1280, 266],
        "rawText": f"measure {measure['number']} from {measure['window']}",
        "confidence": 0.75,
    }


def duration(raw: dict) -> dict:
    return {"base": raw.get("base", 4), "dots": raw.get("dots", 0)}


def bend_curve(kind: str) -> list[dict]:
    if kind == "release-from-full":
        return [{"at": 0, "alter": 2}, {"at": 0.5, "alter": 2}, {"at": 1, "alter": 0}]
    if kind in {"half", "1/2"}:
        return [{"at": 0, "alter": 0}, {"at": 0.7, "alter": 1}, {"at": 1, "alter": 1}]
    if kind in {"quarter", "1/4"}:
        return [{"at": 0, "alter": 0}, {"at": 0.7, "alter": 0.5}, {"at": 1, "alter": 0.5}]
    return [{"at": 0, "alter": 0}, {"at": 0.7, "alter": 2}, {"at": 1, "alter": 2}]


def make_transcribed_event(measure: dict, event_index: int, raw: dict) -> dict:
    number = measure["number"]
    measure_id = f"m{number}"
    event_id = f"{measure_id}e{event_index}"
    src = source_map(measure)
    if raw["type"] == "rest":
        return {
            "id": event_id,
            "type": "rest",
            "tick": raw["tick"],
            "duration": duration(raw),
            "voice": 1,
            "display": None,
            "source": src,
        }

    raw_notes = raw.get("notes") or [{"string": raw["string"], "fret": raw["fret"], "display": raw.get("display"), "status": raw.get("status", "normal")}]
    notes = []
    for note_index, raw_note in enumerate(raw_notes, start=1):
        display = raw_note.get("display") or str(raw_note["fret"])
        status = raw_note.get("status", "normal")
        effects = [{"type": "ghost"}] if status == "ghost" else []
        notes.append(
            {
                "id": f"{measure_id}n{event_index}_{note_index}",
                "string": raw_note["string"],
                "fret": raw_note["fret"],
                "display": display,
                "pitch": None,
                "status": status,
                "leftHandFinger": None,
                "rightHandFinger": None,
                "effects": effects,
                "confidence": 0.72,
            }
        )
    return {
        "id": event_id,
        "type": "note",
        "tick": raw["tick"],
        "duration": duration(raw),
        "voice": 1,
        "beamGroup": raw.get("beam"),
        "notes": notes,
        "articulations": raw.get("articulations", []),
        "source": src,
    }


def aliases(events: list[dict]) -> tuple[dict[str, str], dict[str, str]]:
    note_aliases = {}
    note_counter = 1
    for event in events:
        for note in event.get("notes", []):
            note_aliases[f"n{note_counter}"] = note["id"]
            note_counter += 1
    event_aliases = {f"e{index}": event["id"] for index, event in enumerate(events, start=1)}
    return note_aliases, event_aliases


def make_spanner(measure: dict, index: int, raw: dict, note_aliases: dict, event_aliases: dict) -> dict:
    spanner = {"id": f"m{measure['number']}sp{index}", "type": raw["type"]}
    if "from" in raw:
        spanner["from"] = note_aliases.get(raw["from"], raw["from"])
    if "to" in raw:
        spanner["to"] = note_aliases.get(raw["to"], raw["to"])
    if "fromEvent" in raw:
        spanner["fromEvent"] = event_aliases.get(raw["fromEvent"], raw["fromEvent"])
    if "toEvent" in raw:
        spanner["toEvent"] = event_aliases.get(raw["toEvent"], raw["toEvent"])
    for key in ("label", "direction", "width"):
        if key in raw:
            spanner[key] = raw[key]

    if raw["type"] == "bend":
        spanner["placement"] = "above"
        spanner["line"] = "solid"
        spanner["curve"] = bend_curve(raw.get("curve", "full"))
        spanner["preBend"] = bool(raw.get("preBend", False))
        spanner["withBar"] = False
    elif raw["type"] == "slide":
        spanner["placement"] = "above"
        spanner["line"] = "solid"
        spanner["slideKind"] = raw.get("slideKind", "shift")
    elif raw["type"] in {"hammer-on", "pull-off", "tie", "slur"}:
        spanner["placement"] = raw.get("placement", "below" if raw["type"] == "tie" else "above")
        spanner["line"] = "solid"
        spanner.setdefault("label", None)
    elif raw["type"] == "vibrato":
        spanner["placement"] = "above"
        spanner["line"] = "wavy"
        spanner.setdefault("label", None)
    elif raw["type"] in {"let-ring", "palm-mute"}:
        spanner["placement"] = "above" if raw["type"] == "let-ring" else "below"
        spanner["line"] = "solid" if raw["type"] == "let-ring" else "dashed"
    spanner["source"] = source_map(measure)
    return spanner


def make_transcribed_measure(measure: dict, total: int, data: dict) -> dict:
    number = measure["number"]
    measure_id = f"m{number}"
    events = [make_transcribed_event(measure, index, raw) for index, raw in enumerate(data["events"], start=1)]
    note_aliases, event_aliases = aliases(events)
    spanners = [
        make_spanner(measure, index, raw, note_aliases, event_aliases)
        for index, raw in enumerate(data.get("spanners", []), start=1)
    ]
    return {
        "id": measure_id,
        "number": number,
        "trackId": TRACK_ID,
        "startTick": (number - 1) * MEASURE_TICKS,
        "durationTicks": MEASURE_TICKS,
        "attributes": {"clef": "TAB"} if number == 1 else {},
        "barline": {
            "left": "single",
            "right": "final" if number == total else "single",
            "repeatStart": False,
            "repeatEnd": False,
            "repeatCount": None,
            "ending": None,
        },
        "events": events,
        "spanners": spanners,
        "directions": [],
        "source": source_map(measure),
    }


def make_measure(measure: dict, total: int) -> dict:
    number = measure["number"]
    if number in TRANSCRIPTIONS:
        return make_transcribed_measure(measure, total, TRANSCRIPTIONS[number])

    measure_id = f"m{number}"
    src = source_map(measure)
    return {
        "id": measure_id,
        "number": number,
        "trackId": TRACK_ID,
        "startTick": (number - 1) * MEASURE_TICKS,
        "durationTicks": MEASURE_TICKS,
        "attributes": {"clef": "TAB"} if number == 1 else {},
        "barline": {
            "left": "single",
            "right": "final" if number == total else "single",
            "repeatStart": False,
            "repeatEnd": False,
            "repeatCount": None,
            "ending": None,
        },
        "events": [
            {
                "id": f"{measure_id}e1",
                "type": "rest",
                "tick": 0,
                "duration": {"base": 1, "dots": 0},
                "voice": 1,
                "display": None,
                "source": src,
            }
        ],
        "spanners": [],
        "directions": [
            {
                "id": f"{measure_id}d1",
                "tick": 0,
                "type": "instruction",
                "text": f"待转录: {measure['window']}",
                "placement": "above",
                "source": src,
            }
        ],
        "source": src,
    }


def make_systems(measures: list[dict]) -> list[dict]:
    systems = []
    for index in range(0, len(measures), 4):
        chunk = measures[index : index + 4]
        systems.append(
            {
                "id": f"sys-{len(systems) + 1}",
                "source": {
                    "sourceId": SOURCE_ID,
                    "page": None,
                    "frame": None,
                    "bbox": [0, 0, 1280, 266],
                    "rawText": f"render system for measures {chunk[0]['number']}-{chunk[-1]['number']}",
                    "confidence": 0.75,
                },
                "measureIds": [measure["id"] for measure in chunk],
            }
        )
    return systems


def make_score(measure_map: dict) -> dict:
    total = int(measure_map["measureCount"])
    measures = [make_measure(measure, total) for measure in measure_map["measures"]]
    return {
        "schema": "rokid.music.tab-score",
        "schemaVersion": 1,
        "metadata": {"title": "真的爱你", "artist": "BEYOND"},
        "defaults": {
            "ppq": PPQ,
            "tempo": {"bpm": 75, "beatUnit": 4, "text": None},
            "timeSignature": {"beats": 4, "beatType": 4, "symbol": "normal", "visible": True},
            "keySignature": {"fifths": 0, "mode": "major", "visible": False},
            "tuning": {
                "name": "Standard tuning",
                "capo": 0,
                "strings": [
                    {"number": 1, "pitch": "E4", "gauge": None},
                    {"number": 2, "pitch": "B3", "gauge": None},
                    {"number": 3, "pitch": "G3", "gauge": None},
                    {"number": 4, "pitch": "D3", "gauge": None},
                    {"number": 5, "pitch": "A2", "gauge": None},
                    {"number": 6, "pitch": "E2", "gauge": None},
                ],
            },
            "notation": {
                "staff": "tab",
                "showRhythm": True,
                "showStringLabels": False,
                "colorProfile": "rokid-green",
            },
        },
        "tracks": [
            {
                "id": TRACK_ID,
                "name": "Electric Guitar",
                "instrument": "electric-guitar",
                "midiProgram": 30,
                "stringCount": 6,
                "tuningRef": "defaults.tuning",
                "visible": True,
                "playback": {"muted": False, "solo": False},
            }
        ],
        "systems": make_systems(measures),
        "measures": measures,
    }


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    measure_map_path = root / "data" / "tmp" / "douyin_7379895619067858213" / "measure_map.json"
    out_path = root / "data" / "music" / "zhendeaini_douyin.tab.json"
    measure_map = json.loads(measure_map_path.read_text(encoding="utf-8"))
    out_path.write_text(json.dumps(make_score(measure_map), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main()

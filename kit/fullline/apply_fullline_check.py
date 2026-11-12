#!/usr/bin/env python3
"""apply_fullline.model_version 자체 점검: 묶음 안 model.xml 판을 읽고, 없거나 둘이면 None."""
import tempfile
import zipfile
from pathlib import Path

import apply_fullline as t


def make(path, entries):
    with zipfile.ZipFile(path, "w") as zf:
        for name, text in entries.items():
            zf.writestr(name, text)
    return path


with tempfile.TemporaryDirectory() as work:
    d = Path(work)
    good = make(d / "a.zip", {"java-0.1/model.xml": "<model><version>0.1</version></model>", "java-0.1/x.bin": "b"})
    assert t.model_version(good) == "0.1"
    assert t.model_version(make(d / "b.zip", {"x.bin": "b"})) is None
    assert t.model_version(make(d / "c.zip", {"a/model.xml": "<model/>"})) is None
    two = {"a/model.xml": "<version>1</version>", "b/model.xml": "<version>1</version>"}
    assert t.model_version(make(d / "d.zip", two)) is None
assert t.bundle_name("java") == "full-line-model-java.zip"
print("apply_fullline_check 통과")

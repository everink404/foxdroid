from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase

from foxdroid_server.simfile import parse_simfile

SM_CONTENT = """
#TITLE:Test Song;
#ARTIST:Test Artist;
#MUSIC:test.ogg;
#OFFSET:-0.125;
#BPMS:0.000=120.000,64.000=180.000;
#NOTES:
     dance-single:
     Example:
     Hard:
     7:
     0,0,0,0,0:
0000
1000
0000
0100
;
"""


SSC_CONTENT = """
#VERSION:0.83;
#TITLE:SSC Song;
#ARTIST:Test Artist;
#MUSIC:test.ogg;
#BPMS:0.000=120.000;
#NOTEDATA:;
#STEPSTYPE:dance-single;
#DESCRIPTION:Basic chart;
#DIFFICULTY:Easy;
#METER:3;
#NOTES:
0000
1000
0000
0100
;
"""


class SimfileParserTests(TestCase):
    def _parse(self, name: str, content: str):
        with TemporaryDirectory() as directory:
            path = Path(directory) / name
            path.write_text(content, encoding="utf-8")
            return parse_simfile(path, "Test Pack")

    def test_parses_sm_metadata_timing_and_chart(self) -> None:
        song = self._parse("test.sm", SM_CONTENT)
        self.assertEqual(song.title, "Test Song")
        self.assertEqual(song.preview_start, None)
        self.assertEqual(song.timing["OFFSET"], "-0.125")
        self.assertEqual(song.charts[0].step_type, "dance-single")
        self.assertEqual(song.charts[0].meter, 7)

    def test_parses_ssc_chart_section(self) -> None:
        song = self._parse("test.ssc", SSC_CONTENT)
        self.assertEqual(song.title, "SSC Song")
        self.assertEqual(len(song.charts), 1)
        self.assertEqual(song.charts[0].difficulty, "Easy")
        self.assertEqual(song.charts[0].timing["BPMS"], "0.000=120.000")

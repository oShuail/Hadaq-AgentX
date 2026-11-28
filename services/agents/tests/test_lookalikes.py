"""Look-alike table (DGDA Q8): the 7 pairs, the base-row rule for the dark stain, and the contract's Lookalike shape."""

import json
from pathlib import Path

import pytest

from hadaq_agents import lookalikes as LA
from hadaq_schemas import Finding, Lookalike
from hadaq_schemas.inspection import BONUS_DAMAGE_TYPES, CORE_DAMAGE_TYPES, SUSPECTED_ONLY_TYPES

REPO = Path(__file__).resolve().parents[3]
EXAMPLE = REPO / "contracts" / "v2" / "examples" / "capture_results.json"


def finding(damage_type: str, zone_id: str = "B01-W01-R1C4", **extra) -> Finding:
    return Finding(capture_id="cap_test", building_id="B01", wall_id="W01", zone_id=zone_id, damage_type=damage_type,
                   severity=2, confidence=0.8, suspected=damage_type in SUSPECTED_ONLY_TYPES,
                   box_wall_m=[12.0, 0.0, 13.0, 1.0], model="test@0", **extra)


# ---------- the table ----------

def test_table_has_dgdas_seven_pairs():
    table = LA.load()
    ids = [p["id"] for p in table["pairs"]]
    assert len(ids) == 7 and len(set(ids)) == 7
    assert "dark_stain_base" in ids  # the id the contract example and the core tests use
    assert [p["case_ar"] for p in table["pairs"]] == [
        "شق رأسي", "بقعة داكنة عند القاعدة", "تساقط لياسة", "أخدود على الواجهة", "بقع بيضاء", "سطح متجانس ونظيف",
        "فجوة في الجدار"]


def test_every_pair_is_complete_in_both_languages():
    catalog = LA.evidence()
    sources = LA.load()["sources"]
    for pair in LA.load()["pairs"]:
        assert pair["case_ar"].strip() and pair["case_en"].strip() and pair["note_ar"].strip(), pair["id"]
        assert len(pair["options"]) == 2, pair["id"]
        for option in pair["options"]:
            for key in ("label_ar", "label_en", "treatment_ar", "treatment_en"):
                assert option[key].strip(), (pair["id"], key)
        assert pair["evidence"] and all(eid in catalog for eid in pair["evidence"]), pair["id"]
        assert pair["source"] in sources and pair["rows"] in ("any", "base")
        assert isinstance(pair["assumptions"], list)
    for item in catalog.values():
        assert item["label_ar"].strip() and item["label_en"].strip() and item["source"] in sources


def test_triggers_are_glossary_ids_and_each_type_has_at_most_one_pair():
    allowed = set(CORE_DAMAGE_TYPES) | set(SUSPECTED_ONLY_TYPES)
    seen: list[str] = []
    for pair in LA.load()["pairs"]:
        assert set(pair["damage_types"]) <= allowed, pair["id"]
        seen += pair["damage_types"]
    assert len(seen) == len(set(seen))
    assert set(CORE_DAMAGE_TYPES) <= set(seen)  # every core damage type gets its DGDA question


def test_no_em_dashes_in_the_table():
    assert "\u2014" not in LA.DATA_FILE.read_text(encoding="utf-8")


# ---------- the dark stain only at the base ----------

def test_dark_stain_at_the_base_asks_for_moisture_readings():
    result = LA.lookalike_for(finding("base_erosion", "B01-W01-R1C4"))
    assert isinstance(result, Lookalike)
    assert result.pair_id == "dark_stain_base"
    assert result.options == ["active rising damp", "old dry moisture stain"]
    assert "Moisture readings at several heights of the wall" in result.evidence_needed
    assert result.note_ar == "البقعة الداكنة عند القاعدة قد تكون رطوبة صاعدة نشطة أو أثراً قديماً جافاً"


@pytest.mark.parametrize("zone_id", ["B01-W01-R2C4", "B01-W01-R3C4", "B01-W01-R4C1", "not-a-zone"])
def test_same_finding_higher_up_gets_no_dark_stain_pair(zone_id):
    assert LA.lookalike_for(finding("base_erosion", zone_id)) is None


def test_matches_the_contract_example():
    example = json.loads(EXAMPLE.read_text(encoding="utf-8"))["findings"][0]
    expected = example.pop("lookalike")
    assert LA.lookalike_for(Finding.model_validate(example)).pair_id == expected["pair_id"]


# ---------- every other pair ----------

@pytest.mark.parametrize(("damage_type", "pair_id", "evidence_id"), [
    ("crack", "vertical_crack", "crack_monitoring"),
    ("plaster_loss", "plaster_loss_cause", "intervention_history"),
    ("rain_grooves", "facade_groove", "roof_drainage"),
    ("salt_efflorescence", "white_spots", "soil_salt_samples"),
    ("incompatible_repair", "smooth_surface", "tap_test"),
    ("voids_burrows", "wall_hole", "pest_signs"),
])
def test_each_damage_type_gets_its_pair_on_any_row(damage_type, pair_id, evidence_id):
    label = LA.evidence()[evidence_id]["label_en"]
    for zone_id in ("B01-W01-R1C1", "B01-W01-R3C6"):
        result = LA.lookalike_for(finding(damage_type, zone_id))
        assert result is not None and result.pair_id == pair_id
        assert label in result.evidence_needed and len(result.options) == 2


@pytest.mark.parametrize("damage_type", ["structural_deformation", *BONUS_DAMAGE_TYPES])
def test_types_without_a_dgda_pair_get_none(damage_type):
    assert LA.lookalike_for(finding(damage_type)) is None


def test_every_output_validates_as_the_contract_model():
    for pair in LA.load()["pairs"]:
        for lang in ("en", "ar"):
            out = LA.to_lookalike(pair, lang)
            assert Lookalike.model_validate(out.model_dump()) == out


def test_arabic_options_and_evidence():
    result = LA.lookalike_for(finding("voids_burrows", "B01-W01-R2C2"), lang="ar")
    assert result.options == ["جحر قوارض أو حشرات", "فتحة أصلية (تهوية أو تثبيت خشب)"]
    assert "آثار الحشرات والقوارض" in result.evidence_needed


def test_accepts_a_plain_dict_and_follows_the_conservators_correction():
    assert LA.lookalike_for({"damage_type": "base_erosion", "zone_id": "B01-W01-R1C2"}).pair_id == "dark_stain_base"
    corrected = finding("plaster_loss", "B01-W01-R3C2", review={"status": "corrected", "corrected_type": "crack"})
    assert LA.lookalike_for(corrected).pair_id == "vertical_crack"


def test_markdown_table_has_a_row_per_pair():
    table = LA.markdown_table()
    assert table.count("\n") == 1 + 7
    assert "مراقبة فقط" in table and "(R1)" in table

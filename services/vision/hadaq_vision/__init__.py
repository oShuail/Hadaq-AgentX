"""Hadaq vision (owner: Abdurhmn).

- detect.py      RF-DETR-Seg detector + a mock detector for pipeline testing
- zones.py       photo pixels -> wall zone IDs
- change.py      compare with the previous photo: new / grown / unchanged
- worker.py      polls the API for new photos, detects, posts DetectionBatch
- camera_sim.py  fake fixed camera: uploads a photo series, jumps to damage on new_crack
- train.py       fine-tune RF-DETR-Seg on our COCO dataset
"""

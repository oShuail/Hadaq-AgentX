"""Fine-tune RF-DETR-Seg on our mud-wall dataset.

Dataset: COCO segmentation format, exported from Roboflow ("COCO Segmentation") or CVAT:
    data/hadaq-defects/train/_annotations.coco.json + images
    data/hadaq-defects/valid/_annotations.coco.json + images
    data/hadaq-defects/test/_annotations.coco.json  + images
Classes (exact names): crack, erosion, plaster_loss, moisture_stain, missing_material

    python -m hadaq_vision.train --dataset data/hadaq-defects --epochs 30 --batch 4

Use a GPU (Colab or Kaggle is fine). Split train/valid/test BY WALL, not by photo, or the score is fake.
"""

from __future__ import annotations

import argparse


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch", type=int, default=4)
    parser.add_argument("--grad-accum", type=int, default=4)
    parser.add_argument("--lr", type=float, default=1e-4)
    parser.add_argument("--out", default="runs/hadaq-seg")
    args = parser.parse_args()

    from rfdetr import RFDETRSegMedium

    model = RFDETRSegMedium()
    model.train(
        dataset_dir=args.dataset,
        epochs=args.epochs,
        batch_size=args.batch,
        grad_accum_steps=args.grad_accum,
        lr=args.lr,
        output_dir=args.out,
    )


if __name__ == "__main__":
    main()

// Detailed 3D scans of At-Turaif buildings that we can only show through the author's own Sketchfab embed
// (download is off), so they open in Sketchfab's viewer, always with the author credit (owner: Omar).
export interface EmbeddedScan {
  uid: string;
  title: string;
  author: string;
  authorUrl: string;
  modelUrl: string;
}

export const FOCUS_SCAN: EmbeddedScan = {
  uid: "56efbdda817e40cbbabff4b5f0998ee4",
  title: "AL Turaif House",
  author: "ATS",
  authorUrl: "https://sketchfab.com/ats",
  modelUrl: "https://sketchfab.com/3d-models/al-turaif-house-56efbdda817e40cbbabff4b5f0998ee4",
};

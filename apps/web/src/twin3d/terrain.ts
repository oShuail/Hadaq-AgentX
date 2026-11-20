// Terrain meshes draped with satellite imagery (owner: Omar).
// Fine 10 m grid over the core area, a coarse grid out to the horizon, and a sharper image over At-Turaif.
import * as THREE from "three";

import type { HeightGrid, Site } from "./siteData";

/** Shared look of the imagery: warm, slightly desaturated, like a late-afternoon photo. */
export const groundGrade = {
  uSat: { value: 0.78 },
  uTint: { value: new THREE.Color(1.06, 0.95, 0.82) },
  uGain: { value: 0.92 },
};

function gridGeometry(h: HeightGrid, sink?: (x: number, y: number) => number): THREE.BufferGeometry {
  const { nx, ny } = h.grid;
  const { x0, x1, y0, y1 } = h.box;
  const pos = new Float32Array(nx * ny * 3);
  const uv = new Float32Array(nx * ny * 2);
  for (let j = 0; j < ny; j++)
    for (let i = 0; i < nx; i++) {
      const k = j * nx + i;
      const x = x0 + (i / (nx - 1)) * (x1 - x0);
      const y = y1 - (j / (ny - 1)) * (y1 - y0);
      pos[k * 3] = x;
      pos[k * 3 + 1] = h.data[k] - (sink ? sink(x, y) : 0);
      pos[k * 3 + 2] = -y;
      uv[k * 2] = i / (nx - 1);
      uv[k * 2 + 1] = 1 - j / (ny - 1);
    }
  const index = new Uint32Array((nx - 1) * (ny - 1) * 6);
  let n = 0;
  for (let j = 0; j < ny - 1; j++)
    for (let i = 0; i < nx - 1; i++) {
      const tl = j * nx + i;
      const tr = tl + 1;
      const bl = tl + nx;
      const br = bl + 1;
      // Diagonal from bottom-left to top-right (matches the height sampler in siteData.ts).
      index[n++] = tl; index[n++] = bl; index[n++] = tr;
      index[n++] = bl; index[n++] = br; index[n++] = tr;
    }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  g.setAttribute("uv", new THREE.BufferAttribute(uv, 2));
  g.setIndex(new THREE.BufferAttribute(index, 1));
  g.computeVertexNormals();
  g.computeBoundingSphere();
  return g;
}

function loadTexture(renderer: THREE.WebGLRenderer, file: string): Promise<THREE.Texture> {
  return new THREE.TextureLoader().loadAsync("/site/" + file).then((t) => {
    t.colorSpace = THREE.SRGBColorSpace;
    t.anisotropy = renderer.capabilities.getMaxAnisotropy();
    t.generateMipmaps = true;
    t.minFilter = THREE.LinearMipmapLinearFilter;
    return t;
  });
}

/** Imagery material with the shared grade and, optionally, a sharper image inside a sub-rectangle of the UVs. */
function imageryMaterial(map: THREE.Texture, detail?: { map: THREE.Texture; rect: THREE.Vector4 }): THREE.MeshStandardMaterial {
  const m = new THREE.MeshStandardMaterial({ map, roughness: 1, metalness: 0 });
  m.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, groundGrade);
    shader.uniforms.uDetail = { value: detail?.map ?? null };
    shader.uniforms.uDetailRect = { value: detail?.rect ?? new THREE.Vector4(2, 2, 3, 3) };
    shader.fragmentShader = shader.fragmentShader
      .replace(
        "#include <common>",
        `#include <common>
         uniform float uSat; uniform vec3 uTint; uniform float uGain;
         uniform sampler2D uDetail; uniform vec4 uDetailRect;`,
      )
      .replace(
        "#include <map_fragment>",
        `#include <map_fragment>
         ${detail ? `{
           vec2 duv = (vMapUv - uDetailRect.xy) / (uDetailRect.zw - uDetailRect.xy);
           if (duv.x > 0.0 && duv.y > 0.0 && duv.x < 1.0 && duv.y < 1.0) {
             vec2 edge = min(duv, 1.0 - duv);
             float w = smoothstep(0.0, 0.04, min(edge.x, edge.y));
             diffuseColor.rgb = mix(diffuseColor.rgb, texture2D(uDetail, duv).rgb, w);
           }
         }` : ""}
         float lum = dot(diffuseColor.rgb, vec3(0.299, 0.587, 0.114));
         diffuseColor.rgb = mix(vec3(lum), diffuseColor.rgb, uSat) * uTint * uGain;`,
      );
  };
  return m;
}

export interface TerrainLayer {
  core: THREE.Mesh;
  wide: THREE.Mesh;
}

export async function buildTerrain(site: Site, renderer: THREE.WebGLRenderer): Promise<TerrainLayer> {
  const { imagery, boxes } = site.pack;
  const [wideMap, coreMap, detailMap] = await Promise.all([
    loadTexture(renderer, imagery.wide.file),
    loadTexture(renderer, imagery.core.file),
    loadTexture(renderer, imagery.detail.file),
  ]);

  // Detail image rectangle inside the core image's UV space (v grows north).
  const c = boxes.core;
  const d = boxes.detail;
  const rect = new THREE.Vector4(
    (d.w - c.w) / (c.e - c.w),
    (d.s - c.s) / (c.n - c.s),
    (d.e - c.w) / (c.e - c.w),
    (d.n - c.s) / (c.n - c.s),
  );
  const coreMesh = new THREE.Mesh(gridGeometry(site.core), imageryMaterial(coreMap, { map: detailMap, rect }));
  coreMesh.receiveShadow = true;
  coreMesh.name = "terrain-core";

  // The wide mesh sinks under the core area so the two never fight; a gentle ramp hides the seam.
  const sink = (x: number, y: number) => {
    const inset = Math.min(x - c.x0, c.x1 - x, y - c.y0, c.y1 - y);
    return inset > 0 ? Math.min(25, 3 + inset * 0.2) : 0;
  };
  const wideMesh = new THREE.Mesh(gridGeometry(site.wide, sink), imageryMaterial(wideMap));
  wideMesh.receiveShadow = true;
  wideMesh.name = "terrain-wide";
  return { core: coreMesh, wide: wideMesh };
}
